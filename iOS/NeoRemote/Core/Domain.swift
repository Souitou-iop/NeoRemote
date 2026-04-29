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
    case android

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .macOS:
            return "macOS"
        case .windows:
            return "Windows"
        case .android:
            return "Android"
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

enum VideoActionKind: String, Codable, CaseIterable {
    case swipeUp
    case swipeDown
    case swipeLeft
    case swipeRight
    case doubleTapLike
    case playPause
    case back

    var displayName: String {
        switch self {
        case .swipeUp:
            return "下一条"
        case .swipeDown:
            return "上一条"
        case .swipeLeft:
            return "左滑"
        case .swipeRight:
            return "右滑"
        case .doubleTapLike:
            return "双击点赞"
        case .playPause:
            return "播放/暂停"
        case .back:
            return "返回"
        }
    }
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
    case videoAction(VideoActionKind)
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

struct TouchSensitivitySettings: Equatable {
    static let `default` = TouchSensitivitySettings()

    static let cursorRange: ClosedRange<Double> = 0.4 ... 2.5
    static let swipeRange: ClosedRange<Double> = 0.5 ... 2.0
    static let step: Double = 0.1

    var cursorSensitivity: Double = 1.0
    var swipeSensitivity: Double = 1.0

    var clamped: TouchSensitivitySettings {
        TouchSensitivitySettings(
            cursorSensitivity: cursorSensitivity.clamped(to: Self.cursorRange),
            swipeSensitivity: swipeSensitivity.clamped(to: Self.swipeRange)
        )
    }
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

extension Double {
    func clamped(to range: ClosedRange<Double>) -> Double {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
