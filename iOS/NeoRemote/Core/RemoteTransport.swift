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
    private var decoder = JsonMessageStreamDecoder()
    private var manualDisconnect = false

    init(codec: ProtocolCodec = ProtocolCodec()) {
        self.codec = codec
    }

    func connect(to endpoint: DesktopEndpoint) {
        ConnectionDiagnostics.log("transport-connect-request host=\(endpoint.host) port=\(endpoint.port)")
        cancelConnection(notify: false)
        manualDisconnect = false
        decoder = JsonMessageStreamDecoder()

        let tcpOptions = NWProtocolTCP.Options()
        tcpOptions.noDelay = true

        let parameters = NWParameters(tls: nil, tcp: tcpOptions)
        parameters.allowLocalEndpointReuse = true

        let host = NWEndpoint.Host(endpoint.host)
        let port = NWEndpoint.Port(rawValue: endpoint.port) ?? 50505
        let connection = NWConnection(host: host, port: port, using: parameters)
        self.connection = connection

        onStateChange?(.connecting)
        ConnectionDiagnostics.log("transport-start host=\(endpoint.host) port=\(endpoint.port)")

        connection.stateUpdateHandler = { [weak self] state in
            DispatchQueue.main.async {
                self?.handle(state: state)
            }
        }

        connection.start(queue: .global(qos: .userInitiated))
    }

    func disconnect() {
        cancelConnection(notify: true)
    }

    private func cancelConnection(notify: Bool) {
        manualDisconnect = true
        connection?.cancel()
        connection = nil
        ConnectionDiagnostics.log("transport-disconnect manual=true")
        if notify {
            onStateChange?(.disconnected(errorDescription: nil))
        }
    }

    func send(_ data: Data) {
        ConnectionDiagnostics.log("transport-send bytes=\(data.count) type=\(data.protocolTypeDescription)")
        connection?.send(content: data, completion: .contentProcessed { [weak self] error in
            guard let error else {
                ConnectionDiagnostics.log("transport-send-complete ok")
                return
            }
            DispatchQueue.main.async {
                ConnectionDiagnostics.log("transport-send-complete error=\(error.localizedDescription)")
                self?.onStateChange?(.failed(errorDescription: error.localizedDescription))
            }
        })
    }

    private func handle(state: NWConnection.State) {
        ConnectionDiagnostics.log("transport-state \(state.diagnosticDescription)")
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
                if let data, !data.isEmpty, let self {
                    ConnectionDiagnostics.log("transport-receive bytes=\(data.count) type=\(data.protocolTypeDescription)")
                    do {
                        let payloads = try self.decoder.append(data)
                        payloads.forEach { payload in
                            guard let message = try? self.codec.decode(payload) else { return }
                            self.onMessage?(message)
                        }
                    } catch {
                        self.connection?.cancel()
                        self.onStateChange?(.failed(errorDescription: error.localizedDescription))
                        return
                    }
                }

                if let error {
                    ConnectionDiagnostics.log("transport-receive error=\(error.localizedDescription)")
                    self?.onStateChange?(.failed(errorDescription: error.localizedDescription))
                    return
                }

                if isComplete {
                    ConnectionDiagnostics.log("transport-receive complete")
                    self?.onStateChange?(.disconnected(errorDescription: self?.manualDisconnect == true ? nil : "连接已关闭"))
                    return
                }

                self?.receiveNextMessage()
            }
        }
    }
}

private extension NWConnection.State {
    var diagnosticDescription: String {
        switch self {
        case .setup:
            return "setup"
        case .waiting(let error):
            return "waiting error=\(error.localizedDescription)"
        case .preparing:
            return "preparing"
        case .ready:
            return "ready"
        case .failed(let error):
            return "failed error=\(error.localizedDescription)"
        case .cancelled:
            return "cancelled"
        @unknown default:
            return "unknown"
        }
    }
}

private extension Data {
    var protocolTypeDescription: String {
        guard
            let object = try? JSONSerialization.jsonObject(with: self) as? [String: Any],
            let type = object["type"] as? String
        else {
            return "unknown"
        }
        return type
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

enum JsonMessageStreamDecoderError: Error, LocalizedError, Equatable {
    case bufferLimitExceeded

    var errorDescription: String? {
        "JSON 消息超过 1MB 上限，已断开连接。"
    }
}

struct JsonMessageStreamDecoder {
    static let maxBufferSize = 1_048_576

    private var buffer: [UInt8] = []

    mutating func append(_ data: Data) throws -> [Data] {
        buffer.append(contentsOf: data)
        guard buffer.count <= Self.maxBufferSize else {
            buffer.removeAll(keepingCapacity: false)
            throw JsonMessageStreamDecoderError.bufferLimitExceeded
        }

        var payloads: [Data] = []
        var inString = false
        var escaping = false
        var depth = 0
        var startIndex: Int?
        var consumedThrough: Int?

        for index in buffer.indices {
            let byte = buffer[index]

            if escaping {
                escaping = false
                continue
            }

            if inString && byte == UInt8(ascii: "\\") {
                escaping = true
                continue
            }

            if byte == UInt8(ascii: "\"") {
                inString.toggle()
                continue
            }

            if inString {
                continue
            }

            if byte == UInt8(ascii: "{") {
                if depth == 0 {
                    startIndex = index
                }
                depth += 1
                continue
            }

            if byte == UInt8(ascii: "}") && depth > 0 {
                depth -= 1
                if depth == 0, let payloadStart = startIndex {
                    payloads.append(Data(buffer[payloadStart ... index]))
                    consumedThrough = index
                    startIndex = nil
                }
            }
        }

        if let consumedThrough {
            buffer.removeFirst(consumedThrough + 1)
        }

        return payloads
    }
}
