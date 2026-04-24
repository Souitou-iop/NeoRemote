import Foundation

struct ProtocolCodec {
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    func encode(_ command: RemoteCommand) throws -> Data {
        try encoder.encode(CommandEnvelope(command: command))
    }

    func decode(_ data: Data) throws -> ProtocolMessage {
        let envelope = try decoder.decode(MessageEnvelope.self, from: data)
        switch envelope.type {
        case "ack":
            return .ack
        case "status":
            return .status(envelope.message ?? "")
        case "heartbeat":
            return .heartbeat
        default:
            return .unknown(type: envelope.type)
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

    init(command: RemoteCommand) {
        switch command {
        case let .clientHello(payload):
            type = "clientHello"
            dx = nil
            dy = nil
            deltaX = nil
            deltaY = nil
            button = nil
            state = nil
            clientId = payload.clientId
            displayName = payload.displayName
            platform = payload.platform
        case let .move(dx, dy):
            type = "move"
            self.dx = dx
            self.dy = dy
            deltaX = nil
            deltaY = nil
            button = nil
            state = nil
            clientId = nil
            displayName = nil
            platform = nil
        case let .tap(kind):
            type = "tap"
            dx = nil
            dy = nil
            deltaX = nil
            deltaY = nil
            button = kind
            state = nil
            clientId = nil
            displayName = nil
            platform = nil
        case let .scroll(deltaX, deltaY):
            type = "scroll"
            dx = nil
            dy = nil
            self.deltaX = deltaX
            self.deltaY = deltaY
            button = nil
            state = nil
            clientId = nil
            displayName = nil
            platform = nil
        case let .drag(state, button, dx, dy):
            type = "drag"
            self.dx = dx
            self.dy = dy
            deltaX = nil
            deltaY = nil
            self.button = button
            self.state = state
            clientId = nil
            displayName = nil
            platform = nil
        case .heartbeat:
            type = "heartbeat"
            dx = nil
            dy = nil
            deltaX = nil
            deltaY = nil
            button = nil
            state = nil
            clientId = nil
            displayName = nil
            platform = nil
        }
    }
}

private struct MessageEnvelope: Codable {
    let type: String
    let message: String?
}
