import Foundation

struct ProtocolCodec {
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private let validator = ProtocolCommandValidator()

    func encode(_ message: ProtocolMessage) throws -> Data {
        try encoder.encode(MessageEnvelope(message: message))
    }

    func decodeCommand(_ data: Data) throws -> RemoteCommand {
        let envelope = try decoder.decode(CommandEnvelope.self, from: data)
        switch envelope.type {
        case "clientHello":
            return try validator.validate(
                .clientHello(
                ClientHelloPayload(
                    clientId: envelope.clientId ?? "",
                    displayName: envelope.displayName ?? "",
                    platform: envelope.platform ?? ""
                )
                )
            )
        case "move":
            return try validator.validate(.move(dx: envelope.dx ?? 0, dy: envelope.dy ?? 0))
        case "tap":
            return .tap(kind: envelope.button ?? .primary)
        case "scroll":
            return try validator.validate(.scroll(deltaX: envelope.deltaX ?? 0, deltaY: envelope.deltaY ?? 0))
        case "drag":
            return try validator.validate(.drag(
                state: envelope.state ?? .changed,
                button: envelope.button ?? .primary,
                dx: envelope.dx ?? 0,
                dy: envelope.dy ?? 0
            ))
        case "heartbeat":
            return .heartbeat
        default:
            throw ProtocolCodecError.unknownCommandType(envelope.type)
        }
    }
}

enum ProtocolCodecError: Error, Equatable, LocalizedError {
    case unknownCommandType(String)
    case invalidCommandValue(String)

    var errorDescription: String? {
        switch self {
        case let .unknownCommandType(type):
            return "未识别的命令类型：\(type)"
        case let .invalidCommandValue(message):
            return "协议命令参数无效：\(message)"
        }
    }
}

private struct ProtocolCommandValidator {
    private let maxClientTextLength = 128
    private let maxPointerDelta = 4_096.0
    private let maxScrollDelta = 240.0

    func validate(_ command: RemoteCommand) throws -> RemoteCommand {
        switch command {
        case let .clientHello(payload):
            try validateText(payload.clientId, field: "clientId")
            try validateText(payload.displayName, field: "displayName")
            try validateText(payload.platform, field: "platform")
        case let .move(dx, dy):
            try validateFinite(dx, field: "dx", limit: maxPointerDelta)
            try validateFinite(dy, field: "dy", limit: maxPointerDelta)
        case let .scroll(deltaX, deltaY):
            try validateFinite(deltaX, field: "deltaX", limit: maxScrollDelta)
            try validateFinite(deltaY, field: "deltaY", limit: maxScrollDelta)
        case let .drag(_, _, dx, dy):
            try validateFinite(dx, field: "dx", limit: maxPointerDelta)
            try validateFinite(dy, field: "dy", limit: maxPointerDelta)
        case .tap, .heartbeat:
            break
        }
        return command
    }

    private func validateText(_ value: String, field: String) throws {
        guard value.count <= maxClientTextLength else {
            throw ProtocolCodecError.invalidCommandValue("\(field) 超过 \(maxClientTextLength) 字符")
        }
    }

    private func validateFinite(_ value: Double, field: String, limit: Double) throws {
        guard value.isFinite else {
            throw ProtocolCodecError.invalidCommandValue("\(field) 不是有限数值")
        }
        guard abs(value) <= limit else {
            throw ProtocolCodecError.invalidCommandValue("\(field) 超过允许范围")
        }
    }
}

private struct CommandEnvelope: Codable {
    let type: String
    let dx: Double?
    let dy: Double?
    let deltaX: Double?
    let deltaY: Double?
    let button: MouseButtonKind?
    let state: DragState?
    let clientId: String?
    let displayName: String?
    let platform: String?
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
