import Foundation

enum DesktopDashboardState: Equatable {
    case firstLaunch
    case listeningDisabled
    case missingAccessibilityPermission
    case idleListening
    case connected(RemoteClientEndpoint)
    case occupied(activeEndpoint: RemoteClientEndpoint)
    case error(message: String)
}

struct DashboardEvent: Identifiable, Equatable {
    let id = UUID()
    let title: String
    let detail: String
    let timestamp: Date
}

@MainActor
final class DesktopRemoteService: ObservableObject {
    @Published private(set) var dashboardState: DesktopDashboardState = .firstLaunch
    @Published private(set) var recentEvents: [DashboardEvent] = []
    @Published private(set) var activeClient: RemoteClientEndpoint?
    @Published private(set) var isListening = false
    @Published private(set) var isListeningEnabled = true
    @Published private(set) var isListeningSoundEnabled = true
    @Published private(set) var showsMenuBarExtra = true
    @Published private(set) var permissionStatus: AccessibilityPermissionStatus = .denied
    @Published private(set) var lastErrorMessage: String?

    private let server: RemoteServering
    private let permissionController: AccessibilityPermissionControlling
    private let injector: RemoteCommandInjecting
    private let preferences: AppPreferences
    private let feedbackPlayer: ListeningFeedbackPlaying
    private let listenPort: UInt16

    private var hasBootstrapped = false
    private var activeClientID: UUID?
    private var lastOccupiedAt: Date?
    private var listeningPort: UInt16?

    init(
        server: RemoteServering = TCPRemoteServer(),
        permissionController: AccessibilityPermissionControlling = AccessibilityPermissionController(),
        injector: RemoteCommandInjecting = CGEventInputInjector(),
        preferences: AppPreferences = AppPreferences(),
        feedbackPlayer: ListeningFeedbackPlaying = ListeningFeedbackPlayer(),
        listenPort: UInt16 = 50505
    ) {
        self.server = server
        self.permissionController = permissionController
        self.injector = injector
        self.preferences = preferences
        self.feedbackPlayer = feedbackPlayer
        self.listenPort = listenPort
        self.isListeningEnabled = preferences.isListeningEnabled
        self.isListeningSoundEnabled = preferences.isListeningSoundEnabled
        self.showsMenuBarExtra = preferences.showsMenuBarExtra

        self.server.onEvent = { [weak self] event in
            Task { @MainActor in
                self?.handleServerEvent(event)
            }
        }
    }

    var summaryText: String {
        switch dashboardState {
        case .firstLaunch:
            return "完成首次引导后自动启动服务"
        case .listeningDisabled:
            return "监听功能当前已关闭"
        case .missingAccessibilityPermission:
            return activeClient == nil ? "缺少辅助功能权限" : "已连接，但暂不可控制"
        case .idleListening:
            return "正在监听 \(listeningPort ?? listenPort)"
        case let .connected(endpoint):
            return "正在控制 \(endpoint.displayName)"
        case let .occupied(endpoint):
            return "\(endpoint.displayName) 正在控制中"
        case let .error(message):
            return message
        }
    }

    var menuBarSymbolName: String {
        isListeningEnabled ? "bolt.horizontal.fill" : "bolt.horizontal"
    }

    func bootstrap() {
        guard !hasBootstrapped else { return }
        hasBootstrapped = true

        permissionStatus = permissionController.refresh()
        isListeningEnabled = preferences.isListeningEnabled
        isListeningSoundEnabled = preferences.isListeningSoundEnabled
        showsMenuBarExtra = preferences.showsMenuBarExtra

        if preferences.didCompleteOnboarding {
            if isListeningEnabled {
                startListening()
            } else {
                appendEvent("监听功能保持关闭", detail: "已按上次退出前的状态保留设置")
                recalculateDashboardState()
            }
        } else {
            appendEvent("等待首次引导", detail: "完成后会自动监听局域网请求")
            recalculateDashboardState()
        }
    }

    func completeFirstLaunch() {
        preferences.didCompleteOnboarding = true
        preferences.isListeningEnabled = true
        isListeningEnabled = true
        appendEvent("首次引导完成", detail: "后续启动会自动监听")
        startListening()
    }

    func startListening() {
        guard isListeningEnabled else {
            recalculateDashboardState()
            return
        }
        guard !isListening else {
            recalculateDashboardState()
            return
        }

        do {
            try server.start(port: listenPort)
            lastErrorMessage = nil
        } catch {
            lastErrorMessage = error.localizedDescription
            recalculateDashboardState()
        }
    }

    func stopListening() {
        setListeningEnabled(false)
    }

    func setListeningEnabled(_ enabled: Bool) {
        let wasEnabled = isListeningEnabled
        preferences.isListeningEnabled = enabled
        isListeningEnabled = enabled
        lastErrorMessage = nil

        if enabled {
            if !wasEnabled {
                playListeningFeedback(enabled: true)
                appendEvent("监听功能已开启", detail: "菜单栏开关已启用，服务会按当前状态立即恢复")
            }
            guard preferences.didCompleteOnboarding else {
                recalculateDashboardState()
                return
            }
            startListening()
            return
        }

        if wasEnabled {
            playListeningFeedback(enabled: false)
            appendEvent("监听功能已关闭", detail: "菜单栏开关已关闭，服务停止接收新的控制请求")
        }
        stopListeningRuntime()
    }

    func setListeningSoundEnabled(_ enabled: Bool) {
        preferences.isListeningSoundEnabled = enabled
        isListeningSoundEnabled = enabled
        appendEvent(
            "监听音效已\(enabled ? "开启" : "关闭")",
            detail: enabled ? "后续监听状态变化会播放系统音效" : "后续监听状态变化将保持静默"
        )
    }

    func setMenuBarVisibility(_ visible: Bool) {
        guard showsMenuBarExtra != visible else { return }
        preferences.showsMenuBarExtra = visible
        showsMenuBarExtra = visible
        appendEvent(
            visible ? "菜单栏入口已显示" : "菜单栏入口已隐藏",
            detail: visible ? "可以从系统菜单栏快速查看状态和执行快捷操作" : "应用仍会保留 Dock 图标和主窗口入口"
        )
    }

    private func stopListeningRuntime() {
        server.stop()
        activeClient = nil
        activeClientID = nil
        isListening = false
        listeningPort = nil
        lastErrorMessage = nil
        recalculateDashboardState()
    }

    func disconnectCurrentSession() {
        guard let activeClientID else { return }
        server.disconnect(clientID: activeClientID)
    }

    func requestAccessibilityPermission() {
        let previous = permissionStatus
        permissionStatus = permissionController.requestPrompt()
        handlePermissionChange(from: previous, triggerDetail: "已请求系统授权提示")
    }

    func openAccessibilitySettings() {
        permissionController.openSettings()
        appendEvent("已打开系统设置", detail: "请前往隐私与安全性 > 辅助功能授权 NeoRemote")
    }

    func retryPermissionCheck() {
        let previous = permissionStatus
        permissionStatus = permissionController.refresh()
        handlePermissionChange(from: previous, triggerDetail: "重新检测了辅助功能权限")
    }

    func applicationDidBecomeActive() {
        let previous = permissionStatus
        permissionStatus = permissionController.refresh()
        if previous != permissionStatus {
            handlePermissionChange(from: previous, triggerDetail: "应用恢复前台，权限状态已刷新")
        }
    }

    private func handlePermissionChange(from previous: AccessibilityPermissionStatus, triggerDetail: String) {
        if permissionStatus == .granted, previous != .granted {
            appendEvent("辅助功能权限已就绪", detail: triggerDetail)
            if let activeClientID {
                server.send(.status("辅助功能权限已恢复，可继续控制"), to: activeClientID)
            }
        } else if permissionStatus == .denied {
            appendEvent("缺少辅助功能权限", detail: triggerDetail)
            if let activeClientID {
                server.send(.status("已连接，但缺少辅助功能权限，暂不可控制"), to: activeClientID)
            }
        }
        recalculateDashboardState()
    }

    private func handleServerEvent(_ event: RemoteServerEvent) {
        switch event {
        case let .listenerReady(port):
            isListening = true
            listeningPort = port
            appendEvent("服务已启动", detail: "Bonjour 已发布，TCP 监听端口 \(port)")
            recalculateDashboardState()

        case .listenerStopped:
            isListening = false
            listeningPort = nil
            recalculateDashboardState()

        case let .listenerFailed(message):
            lastErrorMessage = message
            isListening = false
            appendEvent("监听失败", detail: message)
            recalculateDashboardState()

        case let .clientConnected(clientID, endpoint):
            activeClientID = clientID
            activeClient = endpoint
            lastOccupiedAt = nil
            appendEvent("设备已连接", detail: endpoint.addressSummary)
            server.send(.ack, to: clientID)
            if permissionStatus == .granted {
                server.send(.status("已连接 Mac，可开始控制"), to: clientID)
            } else {
                server.send(.status("已连接，但缺少辅助功能权限，暂不可控制"), to: clientID)
            }
            recalculateDashboardState()

        case let .clientRejected(endpoint, reason):
            lastOccupiedAt = Date()
            appendEvent("已拒绝新连接", detail: "\(endpoint.addressSummary) · \(reason)")
            recalculateDashboardState()

        case let .command(clientID, command):
            guard clientID == activeClientID else { return }

            if case .heartbeat = command {
                server.send(.heartbeat, to: clientID)
                if permissionStatus == .denied {
                    server.send(.status("已连接，但缺少辅助功能权限，暂不可控制"), to: clientID)
                }
                return
            }

            guard permissionStatus == .granted else { return }

            do {
                try injector.handle(command)
                lastOccupiedAt = nil
            } catch {
                lastErrorMessage = error.localizedDescription
                appendEvent("输入注入失败", detail: error.localizedDescription)
            }
            recalculateDashboardState()

        case let .clientDisconnected(clientID, endpoint, errorDescription):
            guard clientID == activeClientID else { return }
            activeClientID = nil
            activeClient = nil
            lastOccupiedAt = nil
            appendEvent(
                "设备已断开",
                detail: errorDescription.map { "\(endpoint.addressSummary) · \($0)" } ?? endpoint.addressSummary
            )
            recalculateDashboardState()
        }
    }

    private func recalculateDashboardState() {
        if let lastErrorMessage {
            dashboardState = .error(message: lastErrorMessage)
            return
        }

        if !preferences.didCompleteOnboarding {
            dashboardState = .firstLaunch
            return
        }

        if !isListeningEnabled {
            dashboardState = .listeningDisabled
            return
        }

        if let activeClient {
            if lastOccupiedAt != nil {
                dashboardState = .occupied(activeEndpoint: activeClient)
            } else if permissionStatus == .granted {
                dashboardState = .connected(activeClient)
            } else {
                dashboardState = .missingAccessibilityPermission
            }
            return
        }

        if permissionStatus == .denied {
            dashboardState = .missingAccessibilityPermission
            return
        }

        if isListening {
            dashboardState = .idleListening
        } else {
            dashboardState = .error(message: "服务未启动")
        }
    }

    private func appendEvent(_ title: String, detail: String) {
        recentEvents.insert(
            DashboardEvent(title: title, detail: detail, timestamp: Date()),
            at: 0
        )
        if recentEvents.count > 10 {
            recentEvents = Array(recentEvents.prefix(10))
        }
    }

    private func playListeningFeedback(enabled: Bool) {
        guard isListeningSoundEnabled else { return }
        feedbackPlayer.play(enabled: enabled)
    }
}
