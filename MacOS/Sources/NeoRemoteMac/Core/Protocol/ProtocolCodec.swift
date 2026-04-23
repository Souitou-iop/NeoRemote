import Foundation

struct ProtocolCodec {
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    func encode(_ message: ProtocolMessage) throws -> Data {
        try encoder.encode(MessageEnvelope(message: message))
    }

    func decodeCommand(_ data: Data) throws -> RemoteCommand {
        let envelope = try decoder.decode(CommandEnvelope.self, from: data)
        switch envelope.type {
        case "move":
            return .move(dx: envelope.dx ?? 0, dy: envelope.dy ?? 0)
        case "tap":
            return .tap(kind: envelope.button ?? .primary)
        case "scroll":
            return .scroll(deltaY: envelope.deltaY ?? 0)
        case "drag":
            return .drag(
                state: envelope.state ?? .changed,
                button: envelope.button ?? .primary,
                dx: envelope.dx ?? 0,
                dy: envelope.dy ?? 0
            )
        case "heartbeat":
            return .heartbeat
        default:
            throw ProtocolCodecError.unknownCommandType(envelope.type)
        }
    }
}

enum ProtocolCodecError: Error, Equatable, LocalizedError {
    case unknownCommandType(String)

    var errorDescription: String? {
        switch self {
        case let .unknownCommandType(type):
            return "未识别的命令类型：\(type)"
        }
    }
}

private struct CommandEnvelope: Codable {
    let type: String
    let dx: Double?
    let dy: Double?
    let deltaY: Double?
    let button: MouseButtonKind?
    let state: DragState?
}

private struct MessageEnvelope: Codable {
    let type: String
    let message: String?

    init(message: ProtocolMessage) {
        switch message {
        case .ack:
            type = "ack"
            self.message = nil
        case let .status(text):
            type = "status"
            self.message = text
        case .heartbeat:
            type = "heartbeat"
            self.message = nil
        }
    }
}
