import CoreGraphics
import Foundation

enum SessionStatus: String, Codable, CaseIterable {
    case disconnected
    case discovering
    case connecting
    case connected
    case reconnecting
    case failed
}

enum DesktopPlatform: String, Codable, CaseIterable, Identifiable {
    case macOS
    case windows

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .macOS:
            return "macOS"
        case .windows:
            return "Windows"
        }
    }
}

enum EndpointSource: String, Codable {
    case discovered
    case recent
    case manual
}

struct DesktopEndpoint: Identifiable, Codable, Hashable {
    var id: String
    var displayName: String
    var host: String
    var port: UInt16
    var platform: DesktopPlatform?
    var lastSeenAt: Date?
    var source: EndpointSource

    init(
        id: String = UUID().uuidString,
        displayName: String,
        host: String,
        port: UInt16,
        platform: DesktopPlatform? = nil,
        lastSeenAt: Date? = nil,
        source: EndpointSource
    ) {
        self.id = id
        self.displayName = displayName
        self.host = host
        self.port = port
        self.platform = platform
        self.lastSeenAt = lastSeenAt
        self.source = source
    }
}

enum MouseButtonKind: String, Codable {
    case primary
    case secondary
    case middle

    var displayName: String {
        switch self {
        case .primary:
            return "左键"
        case .secondary:
            return "右键"
        case .middle:
            return "中键"
        }
    }
}

enum DragState: String, Codable {
    case started
    case changed
    case ended
}

enum RemoteCommand: Equatable {
    case move(dx: Double, dy: Double)
    case tap(kind: MouseButtonKind)
    case scroll(deltaY: Double)
    case drag(state: DragState, button: MouseButtonKind, dx: Double, dy: Double)
    case heartbeat
}

enum ProtocolMessage: Equatable {
    case ack
    case status(String)
    case heartbeat
    case unknown(type: String)
}

enum TransportConnectionState: Equatable {
    case idle
    case connecting
    case connected
    case disconnected(errorDescription: String?)
    case failed(errorDescription: String)
}

enum TouchSurfaceSemanticEvent: Equatable {
    case tap(MouseButtonKind)
    case scrolling
    case dragStarted(MouseButtonKind)
    case dragChanged(MouseButtonKind)
    case dragEnded(MouseButtonKind)
}

struct TouchSurfaceOutput: Equatable {
    var commands: [RemoteCommand] = []
    var semanticEvent: TouchSurfaceSemanticEvent?

    static let none = TouchSurfaceOutput()
}

struct ManualConnectDraft: Equatable {
    var host: String = ""
    var port: String = "50505"
}

enum SessionRoute: Equatable {
    case onboarding
    case connected
}

enum ConnectionFailure: LocalizedError, Equatable {
    case invalidHost
    case invalidPort
    case connectionUnavailable

    var errorDescription: String? {
        switch self {
        case .invalidHost:
            return "请输入有效的桌面端地址。"
        case .invalidPort:
            return "请输入有效的端口。"
        case .connectionUnavailable:
            return "当前桌面连接不可用，请稍后重试。"
        }
    }
}

extension CGPoint {
    func distance(to other: CGPoint) -> CGFloat {
        hypot(other.x - x, other.y - y)
    }
}
