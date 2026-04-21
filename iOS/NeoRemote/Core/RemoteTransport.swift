import Foundation
import Network

protocol RemoteTransporting: AnyObject {
    var onStateChange: ((TransportConnectionState) -> Void)? { get set }
    var onMessage: ((ProtocolMessage) -> Void)? { get set }

    func connect(to endpoint: DesktopEndpoint)
    func disconnect()
    func send(_ data: Data)
}

final class TCPRemoteTransport: RemoteTransporting {
    var onStateChange: ((TransportConnectionState) -> Void)?
    var onMessage: ((ProtocolMessage) -> Void)?

    private let codec: ProtocolCodec
    private var connection: NWConnection?

    init(codec: ProtocolCodec = ProtocolCodec()) {
        self.codec = codec
    }

    func connect(to endpoint: DesktopEndpoint) {
        disconnect()

        let tcpOptions = NWProtocolTCP.Options()
        tcpOptions.noDelay = true

        let parameters = NWParameters(tls: nil, tcp: tcpOptions)
        parameters.allowLocalEndpointReuse = true

        let host = NWEndpoint.Host(endpoint.host)
        let port = NWEndpoint.Port(rawValue: endpoint.port) ?? 50505
        let connection = NWConnection(host: host, port: port, using: parameters)
        self.connection = connection

        onStateChange?(.connecting)

        connection.stateUpdateHandler = { [weak self] state in
            DispatchQueue.main.async {
                self?.handle(state: state)
            }
        }

        connection.start(queue: .global(qos: .userInitiated))
    }

    func disconnect() {
        connection?.cancel()
        connection = nil
        onStateChange?(.disconnected(errorDescription: nil))
    }

    func send(_ data: Data) {
        connection?.send(content: data, completion: .contentProcessed { [weak self] error in
            guard let error else { return }
            DispatchQueue.main.async {
                self?.onStateChange?(.failed(errorDescription: error.localizedDescription))
            }
        })
    }

    private func handle(state: NWConnection.State) {
        switch state {
        case .ready:
            onStateChange?(.connected)
            receiveNextMessage()
        case let .failed(error):
            onStateChange?(.failed(errorDescription: error.localizedDescription))
        case let .waiting(error):
            onStateChange?(.disconnected(errorDescription: error.localizedDescription))
        case .cancelled:
            onStateChange?(.disconnected(errorDescription: nil))
        case .setup, .preparing:
            onStateChange?(.connecting)
        @unknown default:
            onStateChange?(.failed(errorDescription: "未知连接状态"))
        }
    }

    private func receiveNextMessage() {
        connection?.receive(minimumIncompleteLength: 1, maximumLength: 1024) { [weak self] data, _, isComplete, error in
            DispatchQueue.main.async {
                if let data, !data.isEmpty, let message = try? self?.codec.decode(data) {
                    self?.onMessage?(message)
                }

                if let error {
                    self?.onStateChange?(.failed(errorDescription: error.localizedDescription))
                    return
                }

                if isComplete {
                    self?.onStateChange?(.disconnected(errorDescription: nil))
                    return
                }

                self?.receiveNextMessage()
            }
        }
    }
}

final class MockRemoteTransport: RemoteTransporting {
    var onStateChange: ((TransportConnectionState) -> Void)?
    var onMessage: ((ProtocolMessage) -> Void)?

    private(set) var sentPayloads: [Data] = []
    var shouldFailOnConnect = false

    func connect(to _: DesktopEndpoint) {
        onStateChange?(shouldFailOnConnect ? .failed(errorDescription: "mock connect failed") : .connected)
    }

    func disconnect() {
        onStateChange?(.disconnected(errorDescription: nil))
    }

    func send(_ data: Data) {
        sentPayloads.append(data)
    }
}
