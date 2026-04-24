import Foundation
import Network

enum MouseButtonKind: String, Codable, Equatable {
    case primary
    case secondary
    case middle
}

enum DragState: String, Codable, Equatable {
    case started
    case changed
    case ended
}

struct ClientHelloPayload: Codable, Equatable {
    let clientId: String
    let displayName: String
    let platform: String
}

enum RemoteCommand: Equatable {
    case clientHello(ClientHelloPayload)
    case move(dx: Double, dy: Double)
    case tap(kind: MouseButtonKind)
    case scroll(deltaX: Double = 0, deltaY: Double = 0)
    case drag(state: DragState, button: MouseButtonKind, dx: Double, dy: Double)
    case heartbeat
}

enum ProtocolMessage: Equatable {
    case ack
    case status(String)
    case heartbeat
}

struct RemoteClientEndpoint: Equatable, Hashable {
    let host: String
    let port: UInt16

    var displayName: String { host }
    var addressSummary: String { "\(host):\(port)" }
}

extension RemoteClientEndpoint {
    init(endpoint: NWEndpoint) {
        switch endpoint {
        case let .hostPort(host, port):
            self.host = host.debugDescription.replacingOccurrences(of: "\"", with: "")
            self.port = port.rawValue
        default:
            self.host = "Unknown"
            self.port = 0
        }
    }
}
