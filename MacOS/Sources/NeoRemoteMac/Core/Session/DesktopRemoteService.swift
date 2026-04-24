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

enum RemoteClientConnectionStatus: String, Equatable {
    case connected
    case disconnected
}

struct ConnectedRemoteClient: Identifiable, Equatable {
    let id: UUID
    var deviceId: String
    var endpoint: RemoteClientEndpoint
    var displayName: String
    var platform: String?
    var status: RemoteClientConnectionStatus
    var connectedAt: Date
    var lastSeenAt: Date
    var isTrusted: Bool
}

struct PendingConnectionRequest: Identifiable, Equatable {
    let id: UUID
    var deviceId: String
    var endpoint: RemoteClientEndpoint
    var displayName: String?
    var platform: String?
    var requestedAt: Date
}

enum ConnectionHistoryEvent: String, Equatable {
    case requested
    case approved
    case rejected
    case disconnected
    case failed
}

struct ConnectionHistoryEntry: Identifiable, Equatable {
    let id = UUID()
    let deviceId: String
    let displayName: String
    let platform: String?
    let endpointSummary: String
    let event: ConnectionHistoryEvent
    let timestamp: Date
    let reason: String?
}

struct ConnectionPermissionPolicy: Equatable {
    var autoAllowTrustedDevices: Bool = true
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
    @Published private(set) var connectionAddresses: [DesktopConnectionAddress] = []
    @Published private(set) var connectedClients: [ConnectedRemoteClient] = []
    @Published private(set) var pendingConnectionRequests: [PendingConnectionRequest] = []
    @Published private(set) var connectionHistory: [ConnectionHistoryEntry] = []
    @Published private(set) var permissionPolicy = ConnectionPermissionPolicy()

    private let server: RemoteServering
    private let permissionController: AccessibilityPermissionControlling
    private let injector: RemoteCommandInjecting
    private let preferences: AppPreferences
    private let feedbackPlayer: ListeningFeedbackPlaying
    private let addressProvider: DesktopConnectionAddressProviding
    private let listenPort: UInt16
    private let inputQueue = DispatchQueue(label: "com.neoremote.mac.input-injection", qos: .userInteractive)

    private var hasBootstrapped = false
    private var lastActiveClientID: UUID?
    private var lastClientActivityRefresh: [UUID: Date] = [:]
    private var listeningPort: UInt16?
    private var isListeningStarting = false

    init(
        server: RemoteServering = TCPRemoteServer(),
        permissionController: AccessibilityPermissionControlling = AccessibilityPermissionController(),
        injector: RemoteCommandInjecting = CGEventInputInjector(),
        preferences: AppPreferences = AppPreferences(),
        feedbackPlayer: ListeningFeedbackPlaying = ListeningFeedbackPlayer(),
        addressProvider: DesktopConnectionAddressProviding = SystemDesktopConnectionAddressProvider(),
        listenPort: UInt16 = 50505
    ) {
        self.server = server
        self.permissionController = permissionController
        self.injector = injector
        self.preferences = preferences
        self.feedbackPlayer = feedbackPlayer
        self.addressProvider = addressProvider
        self.listenPort = listenPort
        self.isListeningEnabled = preferences.isListeningEnabled
        self.isListeningSoundEnabled = preferences.isListeningSoundEnabled
        self.showsMenuBarExtra = preferences.showsMenuBarExtra
        self.permissionPolicy = ConnectionPermissionPolicy(
            autoAllowTrustedDevices: preferences.autoAllowTrustedDevices
        )

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
            let count = connectedClients.count
            return count > 1 ? "\(count) 台设备已连接，当前由 \(endpoint.displayName) 控制" : "正在控制 \(endpoint.displayName)"
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
        permissionPolicy = ConnectionPermissionPolicy(
            autoAllowTrustedDevices: preferences.autoAllowTrustedDevices
        )
        refreshConnectionAddresses()

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
        guard !isListeningStarting else {
            recalculateDashboardState()
            return
        }

        do {
            isListeningStarting = true
            try server.start(port: listenPort)
            lastErrorMessage = nil
            recalculateDashboardState()
        } catch {
            isListeningStarting = false
            lastErrorMessage = error.localizedDescription
            recalculateDashboardState()
        }
    }

    func refreshConnectionAddresses() {
        connectionAddresses = addressProvider.loadConnectionAddresses(port: listenPort)
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
        pendingConnectionRequests.forEach {
            appendConnectionHistory(for: $0, event: .disconnected, reason: "停止监听")
        }
        connectedClients.forEach {
            appendConnectionHistory(for: $0, event: .disconnected, reason: "停止监听")
        }
        server.stop()
        activeClient = nil
        lastActiveClientID = nil
        lastClientActivityRefresh.removeAll()
        pendingConnectionRequests.removeAll()
        connectedClients.removeAll()
        isListeningStarting = false
        isListening = false
        listeningPort = nil
        lastErrorMessage = nil
        recalculateDashboardState()
    }

    func disconnectCurrentSession() {
        guard let clientID = lastActiveClientID ?? connectedClients.last?.id else { return }
        disconnectClient(clientID: clientID)
    }

    func approveConnection(requestID: UUID) {
        guard let request = pendingConnectionRequests.first(where: { $0.id == requestID }) else { return }
        approve(request)
    }

    func rejectConnection(requestID: UUID) {
        guard let request = pendingConnectionRequests.first(where: { $0.id == requestID }) else { return }
        pendingConnectionRequests.removeAll { $0.id == requestID }
        appendConnectionHistory(for: request, event: .rejected, reason: "用户拒绝")
        appendEvent("连接已拒绝", detail: request.summaryText)
        server.send(.status("连接已被 Mac 拒绝"), to: request.id)
        server.disconnect(clientID: request.id)
        recalculateDashboardState()
    }

    func disconnectClient(clientID: UUID) {
        guard connectedClients.contains(where: { $0.id == clientID }) else { return }
        server.disconnect(clientID: clientID)
    }

    func disconnectClients(clientIDs: Set<UUID>) {
        clientIDs.forEach(disconnectClient)
    }

    func setAutoAllowTrustedDevices(_ enabled: Bool) {
        preferences.autoAllowTrustedDevices = enabled
        permissionPolicy.autoAllowTrustedDevices = enabled
        appendEvent(
            enabled ? "已开启历史设备自动允许" : "已关闭历史设备自动允许",
            detail: enabled ? "已允许过的移动端下次会自动连接" : "每次连接都需要手动审批"
        )
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
            if let clientID = lastActiveClientID {
                server.send(.status("辅助功能权限已恢复，可继续控制"), to: clientID)
            }
        } else if permissionStatus == .denied {
            appendEvent("缺少辅助功能权限", detail: triggerDetail)
            if let clientID = lastActiveClientID {
                server.send(.status("已连接，但缺少辅助功能权限，暂不可控制"), to: clientID)
            }
        }
        recalculateDashboardState()
    }

    private func handleServerEvent(_ event: RemoteServerEvent) {
        switch event {
        case let .listenerReady(port):
            isListeningStarting = false
            isListening = true
            listeningPort = port
            refreshConnectionAddresses()
            appendEvent("服务已启动", detail: "Bonjour 已发布，TCP 监听端口 \(port)")
            recalculateDashboardState()

        case .listenerStopped:
            isListeningStarting = false
            isListening = false
            listeningPort = nil
            recalculateDashboardState()

        case let .listenerFailed(message):
            isListeningStarting = false
            lastErrorMessage = message
            isListening = false
            appendEvent("监听失败", detail: message)
            recalculateDashboardState()

        case let .clientConnected(clientID, endpoint):
            let request = PendingConnectionRequest(
                id: clientID,
                deviceId: endpoint.temporaryDeviceId,
                endpoint: endpoint,
                displayName: nil,
                platform: nil,
                requestedAt: Date()
            )
            pendingConnectionRequests.removeAll { $0.id == clientID }
            pendingConnectionRequests.append(request)
            appendConnectionHistory(for: request, event: .requested, reason: nil)
            appendEvent("收到连接请求", detail: request.summaryText)

            if shouldAutoApprove(deviceId: request.deviceId) {
                approve(request)
            } else {
                server.send(.status("正在等待 Mac 允许连接"), to: clientID)
            }
            recalculateDashboardState()

        case let .clientRejected(endpoint, reason):
            appendEvent("已拒绝新连接", detail: "\(endpoint.addressSummary) · \(reason)")
            recalculateDashboardState()

        case let .command(clientID, command):
            if case let .clientHello(payload) = command {
                handleClientHello(payload, from: clientID)
                return
            }

            guard connectedClients.contains(where: { $0.id == clientID }) else { return }

            if case .heartbeat = command {
                markClientActive(clientID, refreshPublishedActivity: true)
                server.send(.heartbeat, to: clientID)
                if permissionStatus == .denied {
                    server.send(.status("已连接，但缺少辅助功能权限，暂不可控制"), to: clientID)
                }
                return
            }

            markClientActive(clientID, refreshPublishedActivity: shouldRefreshActivity(for: clientID))

            guard permissionStatus == .granted else { return }

            let injector = self.injector
            inputQueue.async { [weak self] in
                do {
                    try injector.handle(command)
                } catch {
                    Task { @MainActor in
                        self?.lastErrorMessage = error.localizedDescription
                        self?.appendEvent("输入注入失败", detail: error.localizedDescription)
                        self?.recalculateDashboardState()
                    }
                }
            }

        case let .clientDisconnected(clientID, endpoint, errorDescription):
            if let request = pendingConnectionRequests.first(where: { $0.id == clientID }) {
                pendingConnectionRequests.removeAll { $0.id == clientID }
                appendConnectionHistory(for: request, event: .disconnected, reason: errorDescription)
            }
            if let client = connectedClients.first(where: { $0.id == clientID }) {
                connectedClients.removeAll { $0.id == clientID }
                appendConnectionHistory(for: client, event: .disconnected, reason: errorDescription)
            }
            if lastActiveClientID == clientID {
                lastActiveClientID = connectedClients.last?.id
            }
            lastClientActivityRefresh.removeValue(forKey: clientID)
            activeClient = connectedClients.first(where: { $0.id == lastActiveClientID })?.endpoint ?? connectedClients.last?.endpoint
            appendEvent("设备已断开", detail: errorDescription.map { "\(endpoint.addressSummary) · \($0)" } ?? endpoint.addressSummary)
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
            if permissionStatus == .granted {
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

        if isListening || isListeningStarting {
            dashboardState = .idleListening
        } else {
            dashboardState = .error(message: "服务未启动")
        }
    }

    private func handleClientHello(_ payload: ClientHelloPayload, from clientID: UUID) {
        guard !payload.clientId.isEmpty else { return }

        if let requestIndex = pendingConnectionRequests.firstIndex(where: { $0.id == clientID }) {
            pendingConnectionRequests[requestIndex].deviceId = payload.clientId
            pendingConnectionRequests[requestIndex].displayName = payload.displayName.nilIfBlank
            pendingConnectionRequests[requestIndex].platform = payload.platform.nilIfBlank
            let request = pendingConnectionRequests[requestIndex]
            appendEvent("连接请求已识别", detail: request.summaryText)
            if shouldAutoApprove(deviceId: request.deviceId) {
                approve(request)
            }
            recalculateDashboardState()
            return
        }

        guard let clientIndex = connectedClients.firstIndex(where: { $0.id == clientID }) else { return }
        connectedClients[clientIndex].deviceId = payload.clientId
        connectedClients[clientIndex].displayName = payload.displayName.nilIfBlank ?? connectedClients[clientIndex].displayName
        connectedClients[clientIndex].platform = payload.platform.nilIfBlank
        if connectedClients[clientIndex].isTrusted {
            var trustedIDs = preferences.trustedDeviceIDs
            trustedIDs.insert(payload.clientId)
            preferences.trustedDeviceIDs = trustedIDs
        }
        connectedClients[clientIndex].isTrusted = preferences.trustedDeviceIDs.contains(payload.clientId)
        markClientActive(clientID, refreshPublishedActivity: true)
        recalculateDashboardState()
    }

    private func approve(_ request: PendingConnectionRequest) {
        pendingConnectionRequests.removeAll { $0.id == request.id }
        var trustedIDs = preferences.trustedDeviceIDs
        trustedIDs.insert(request.deviceId)
        preferences.trustedDeviceIDs = trustedIDs

        let client = ConnectedRemoteClient(
            id: request.id,
            deviceId: request.deviceId,
            endpoint: request.endpoint,
            displayName: request.displayName ?? request.endpoint.displayName,
            platform: request.platform,
            status: .connected,
            connectedAt: Date(),
            lastSeenAt: Date(),
            isTrusted: true
        )
        connectedClients.removeAll { $0.id == client.id }
        connectedClients.append(client)
        markClientActive(client.id, refreshPublishedActivity: true)
        appendConnectionHistory(for: client, event: .approved, reason: nil)
        appendEvent("设备已允许", detail: client.summaryText)
        server.send(.ack, to: client.id)
        server.send(permissionStatus == .granted ? .status("已连接 Mac，可开始控制") : .status("已连接，但缺少辅助功能权限，暂不可控制"), to: client.id)
        recalculateDashboardState()
    }

    private func shouldAutoApprove(deviceId: String) -> Bool {
        permissionPolicy.autoAllowTrustedDevices && preferences.trustedDeviceIDs.contains(deviceId)
    }

    private func markClientActive(_ clientID: UUID, refreshPublishedActivity: Bool = false) {
        lastActiveClientID = clientID
        if let index = connectedClients.firstIndex(where: { $0.id == clientID }) {
            let now = Date()
            if refreshPublishedActivity {
                connectedClients[index].lastSeenAt = now
                lastClientActivityRefresh[clientID] = now
            }
            activeClient = connectedClients[index].endpoint
        }
    }

    private func shouldRefreshActivity(for clientID: UUID) -> Bool {
        let now = Date()
        let lastRefresh = lastClientActivityRefresh[clientID] ?? .distantPast
        guard now.timeIntervalSince(lastRefresh) >= 0.5 else { return false }
        lastClientActivityRefresh[clientID] = now
        return true
    }

    private func appendConnectionHistory(for request: PendingConnectionRequest, event: ConnectionHistoryEvent, reason: String?) {
        appendConnectionHistory(
            deviceId: request.deviceId,
            displayName: request.displayName ?? request.endpoint.displayName,
            platform: request.platform,
            endpointSummary: request.endpoint.addressSummary,
            event: event,
            reason: reason
        )
    }

    private func appendConnectionHistory(for client: ConnectedRemoteClient, event: ConnectionHistoryEvent, reason: String?) {
        appendConnectionHistory(
            deviceId: client.deviceId,
            displayName: client.displayName,
            platform: client.platform,
            endpointSummary: client.endpoint.addressSummary,
            event: event,
            reason: reason
        )
    }

    private func appendConnectionHistory(
        deviceId: String,
        displayName: String,
        platform: String?,
        endpointSummary: String,
        event: ConnectionHistoryEvent,
        reason: String?
    ) {
        connectionHistory.insert(
            ConnectionHistoryEntry(
                deviceId: deviceId,
                displayName: displayName,
                platform: platform,
                endpointSummary: endpointSummary,
                event: event,
                timestamp: Date(),
                reason: reason
            ),
            at: 0
        )
        if connectionHistory.count > 50 {
            connectionHistory = Array(connectionHistory.prefix(50))
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

private extension PendingConnectionRequest {
    var summaryText: String {
        let name = displayName ?? endpoint.displayName
        return "\(name) · \(endpoint.addressSummary)"
    }
}

private extension ConnectedRemoteClient {
    var summaryText: String {
        "\(displayName) · \(endpoint.addressSummary)"
    }
}

private extension RemoteClientEndpoint {
    var temporaryDeviceId: String { "endpoint:\(host):\(port)" }
}

private extension String {
    var nilIfBlank: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
