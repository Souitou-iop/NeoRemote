import Foundation
import Network

enum RemoteServerEvent {
    case listenerReady(port: UInt16)
    case listenerStopped
    case listenerFailed(String)
    case clientConnected(UUID, RemoteClientEndpoint)
    case clientRejected(RemoteClientEndpoint, reason: String)
    case command(UUID, RemoteCommand)
    case clientDisconnected(UUID, RemoteClientEndpoint, errorDescription: String?)
}

protocol RemoteServering: AnyObject {
    var onEvent: ((RemoteServerEvent) -> Void)? { get set }
    func start(port: UInt16) throws
    func stop()
    func send(_ message: ProtocolMessage, to clientID: UUID)
    func disconnect(clientID: UUID)
}

final class TCPRemoteServer: RemoteServering, @unchecked Sendable {
    var onEvent: ((RemoteServerEvent) -> Void)?

    private let queue = DispatchQueue(label: "com.neoremote.mac.server")
    private let codec: ProtocolCodec
    private let discoveryResponder: UDPDiscoveryResponder
    private var listener: NWListener?
    private var clients: [UUID: ClientConnection] = [:]
    private var currentPort: UInt16 = 50505

    init(
        codec: ProtocolCodec = ProtocolCodec(),
        discoveryResponder: UDPDiscoveryResponder = UDPDiscoveryResponder()
    ) {
        self.codec = codec
        self.discoveryResponder = discoveryResponder
    }

    func start(port: UInt16) throws {
        stop()

        currentPort = port
        let tcpOptions = NWProtocolTCP.Options()
        tcpOptions.noDelay = true

        let parameters = NWParameters(tls: nil, tcp: tcpOptions)
        parameters.allowLocalEndpointReuse = true
        parameters.includePeerToPeer = true

        let listener = try NWListener(using: parameters, on: NWEndpoint.Port(rawValue: port)!)
        listener.service = NWListener.Service(
            name: Host.current().localizedName ?? "NeoRemote Mac",
            type: "_neoremote._tcp."
        )

        listener.newConnectionHandler = { [weak self] connection in
            self?.handleNewConnection(connection)
        }

        listener.stateUpdateHandler = { [weak self] state in
            self?.queue.async {
                self?.handleListenerState(state)
            }
        }

        self.listener = listener
        listener.start(queue: queue)
        try? discoveryResponder.start(servicePort: port)
    }

    func stop() {
        discoveryResponder.stop()
        listener?.cancel()
        listener = nil

        clients.values.forEach { $0.close() }
        clients.removeAll()
    }

    func send(_ message: ProtocolMessage, to clientID: UUID) {
        guard let client = clients[clientID], let payload = try? codec.encode(message) else { return }
        client.send(payload)
    }

    func disconnect(clientID: UUID) {
        clients[clientID]?.close()
    }

    private func handleListenerState(_ state: NWListener.State) {
        switch state {
        case .ready:
            onEvent?(.listenerReady(port: currentPort))
        case let .failed(error):
            onEvent?(.listenerFailed(error.localizedDescription))
        case .cancelled:
            onEvent?(.listenerStopped)
        case .setup, .waiting:
            break
        @unknown default:
            onEvent?(.listenerFailed("未知监听状态"))
        }
    }

    private func handleNewConnection(_ connection: NWConnection) {
        let id = UUID()

        let client = ClientConnection(id: id, connection: connection, codec: codec, queue: queue)
        clients[id] = client

        client.onReady = { [weak self] clientID, endpoint in
            guard let self else { return }
            self.onEvent?(.clientConnected(clientID, endpoint))
        }

        client.onCommand = { [weak self] clientID, command in
            self?.onEvent?(.command(clientID, command))
        }

        client.onDisconnected = { [weak self] clientID, endpoint, errorDescription in
            guard let self else { return }
            self.clients.removeValue(forKey: clientID)
            self.onEvent?(.clientDisconnected(clientID, endpoint, errorDescription: errorDescription))
        }

        client.start()
    }

}

private final class ClientConnection: @unchecked Sendable {
    let id: UUID

    var onReady: ((UUID, RemoteClientEndpoint) -> Void)?
    var onCommand: ((UUID, RemoteCommand) -> Void)?
    var onDisconnected: ((UUID, RemoteClientEndpoint, String?) -> Void)?

    private let connection: NWConnection
    private let codec: ProtocolCodec
    private let queue: DispatchQueue
    private let endpoint: RemoteClientEndpoint
    private var decoder = JSONMessageStreamDecoder()
    private var hasDisconnected = false

    init(id: UUID, connection: NWConnection, codec: ProtocolCodec, queue: DispatchQueue) {
        self.id = id
        self.connection = connection
        self.codec = codec
        self.queue = queue
        self.endpoint = RemoteClientEndpoint(endpoint: connection.endpoint)
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            self?.queue.async {
                self?.handle(state)
            }
        }
        connection.start(queue: queue)
    }

    func send(_ data: Data) {
        connection.send(content: data, completion: .contentProcessed { _ in })
    }

    func close() {
        connection.cancel()
    }

    private func handle(_ state: NWConnection.State) {
        switch state {
        case .ready:
            onReady?(id, endpoint)
            receiveLoop()
        case let .failed(error):
            disconnect(with: error.localizedDescription)
        case let .waiting(error):
            disconnect(with: error.localizedDescription)
        case .cancelled:
            disconnect(with: nil)
        case .setup, .preparing:
            break
        @unknown default:
            disconnect(with: "未知连接状态")
        }
    }

    private func receiveLoop() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            self.queue.async {
                if let data, !data.isEmpty {
                    self.decoder.append(data).forEach { payload in
                        if let command = try? self.codec.decodeCommand(payload) {
                            self.onCommand?(self.id, command)
                        }
                    }
                }

                if let error {
                    self.disconnect(with: error.localizedDescription)
                    return
                }

                if isComplete {
                    self.disconnect(with: nil)
                    return
                }

                self.receiveLoop()
            }
        }
    }

    private func disconnect(with errorDescription: String?) {
        guard !hasDisconnected else { return }
        hasDisconnected = true
        onDisconnected?(id, endpoint, errorDescription)
    }
}
