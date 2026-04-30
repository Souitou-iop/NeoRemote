import Foundation
import Combine
import UIKit

@MainActor
final class SessionCoordinator: ObservableObject {
    private let registry: DeviceRegistry
    private let discoveryService: DiscoveryServing
    private let transportFactory: () -> any RemoteTransporting
    private let codec: ProtocolCodec
    private let haptics: HapticsController

    private var transport: (any RemoteTransporting)?
    private var activeTransportID: ObjectIdentifier?
    private var hudClearTask: Task<Void, Never>?
    private var heartbeatTask: Task<Void, Never>?
    private var hasStarted = false
    private var isInBackground = false

    @Published var status: SessionStatus = .disconnected
    @Published var route: SessionRoute = .onboarding
    @Published var discoveredDevices: [DesktopEndpoint] = []
    @Published var recentDevices: [DesktopEndpoint] = []
    @Published var activeEndpoint: DesktopEndpoint?
    @Published var lastConnectedEndpoint: DesktopEndpoint?
    @Published var lastHUDMessage: String?
    @Published var errorMessage: String?
    @Published var isHapticsEnabled: Bool
    @Published var controlMode: ControlMode
    @Published var defaultControlMode: ControlMode
    @Published var touchSensitivitySettings: TouchSensitivitySettings
    @Published var statusMessage: String = "等待连接桌面端"
    @Published var manualConnectDraft = ManualConnectDraft()

    init(
        registry: DeviceRegistry = DeviceRegistry(),
        discoveryService: DiscoveryServing = BonjourDiscoveryService(),
        transportFactory: @escaping () -> any RemoteTransporting = { TCPRemoteTransport() },
        codec: ProtocolCodec = ProtocolCodec(),
        haptics: HapticsController = HapticsController()
    ) {
        self.registry = registry
        self.discoveryService = discoveryService
        self.transportFactory = transportFactory
        self.codec = codec
        self.haptics = haptics
        self.isHapticsEnabled = registry.loadHapticsEnabled()
        let initialControlMode = registry.loadControlMode()
        self.controlMode = initialControlMode
        self.defaultControlMode = initialControlMode
        self.touchSensitivitySettings = registry.loadTouchSensitivitySettings()

        self.haptics.isEnabled = self.isHapticsEnabled
        self.recentDevices = registry.loadRecentDevices()
        self.lastConnectedEndpoint = registry.loadLastConnectedDevice()
    }

    func start(startInDemo: Bool = false) {
        guard !hasStarted else { return }
        hasStarted = true

        haptics.prepare()
        if startInDemo {
            enterDemoMode()
            return
        }

        bindDiscovery()
        discoveryService.start()

        if let lastConnectedEndpoint {
            connect(to: lastConnectedEndpoint, isRecovery: true)
        } else {
            status = .discovering
            statusMessage = "正在扫描局域网桌面端"
            route = .onboarding
        }
    }

    func refreshDiscovery() {
        guard !isInBackground else { return }
        status = activeEndpoint == nil ? .discovering : status
        statusMessage = "重新扫描桌面端"
        discoveryService.refresh()
    }

    func handleAppDidBecomeActive() {
        isInBackground = false
        haptics.prepare()

        if status == .connected {
            startHeartbeat()
            if let activeEndpoint {
                statusMessage = "已连接 \(activeEndpoint.displayName)"
            }
        } else if hasStarted {
            refreshDiscovery()
        }
    }

    func handleAppDidEnterBackground() {
        isInBackground = true
        hudClearTask?.cancel()
        hudClearTask = nil
        lastHUDMessage = nil
        heartbeatTask?.cancel()
        heartbeatTask = nil
        discoveryService.stop()
    }

    func connectUsingManualDraft() {
        do {
            let endpoint = try registry.validate(host: manualConnectDraft.host, portText: manualConnectDraft.port)
            connect(to: endpoint, isRecovery: false)
        } catch {
            errorMessage = error.localizedDescription
            status = .failed
            route = .onboarding
        }
    }

    func connectUsingWiredMacAddress(host: String) {
        manualConnectDraft = ManualConnectDraft(host: host, port: "50505")
        connectUsingManualDraft()
    }

    func connect(to endpoint: DesktopEndpoint, isRecovery: Bool = false) {
        errorMessage = nil
        activeEndpoint = endpoint
        status = isRecovery ? .reconnecting : .connecting
        route = isRecovery ? route : .onboarding
        statusMessage = isRecovery ? "正在恢复与 \(endpoint.displayName) 的连接" : "正在连接 \(endpoint.displayName)"

        let previousTransport = transport
        transport = nil
        activeTransportID = nil
        previousTransport?.disconnect()

        let transport = transportFactory()
        let transportID = ObjectIdentifier(transport)
        self.transport = transport
        activeTransportID = transportID

        bindTransport(transport, transportID: transportID)
        transport.connect(to: endpoint)
    }

    func disconnect() {
        heartbeatTask?.cancel()
        heartbeatTask = nil
        let activeTransport = transport
        transport = nil
        activeTransportID = nil
        activeEndpoint = nil
        status = .disconnected
        route = .onboarding
        statusMessage = "已断开连接"
        activeTransport?.disconnect()
    }

    func clearRecentDevices() {
        registry.clearRecentDevices()
        recentDevices = []
        lastConnectedEndpoint = nil
    }

    func setHapticsEnabled(_ isEnabled: Bool) {
        isHapticsEnabled = isEnabled
        haptics.isEnabled = isEnabled
        registry.saveHapticsEnabled(isEnabled)

        if isEnabled {
            haptics.prepare()
        }
    }

    func setCursorSensitivity(_ value: Double) {
        updateTouchSensitivity(
            TouchSensitivitySettings(
                cursorSensitivity: value,
                swipeSensitivity: touchSensitivitySettings.swipeSensitivity
            )
        )
    }

    func setSwipeSensitivity(_ value: Double) {
        updateTouchSensitivity(
            TouchSensitivitySettings(
                cursorSensitivity: touchSensitivitySettings.cursorSensitivity,
                swipeSensitivity: value
            )
        )
    }

    func setControlMode(_ mode: ControlMode) {
        controlMode = mode
    }

    func setDefaultControlMode(_ mode: ControlMode) {
        defaultControlMode = mode
        registry.saveControlMode(mode)
    }

    func enterDemoMode() {
        heartbeatTask?.cancel()
        heartbeatTask = nil
        discoveryService.stop()

        let demoEndpoint = DesktopEndpoint(
            id: "demo-endpoint",
            displayName: "NeoRemote Demo",
            host: "demo.local",
            port: 50505,
            platform: .macOS,
            lastSeenAt: Date(),
            source: .manual
        )

        activeEndpoint = demoEndpoint
        transport = MockRemoteTransport()
        activeTransportID = nil
        status = .connected
        route = .connected
        errorMessage = nil
        statusMessage = "功能演示"
        showHUD("演示模式")
    }

    func send(_ command: RemoteCommand) {
        guard status == .connected else { return }
        guard let transport else { return }

        do {
            let payload = try codec.encode(command)
            transport.send(payload)
            handleSemanticUpdate(for: command)
        } catch {
            errorMessage = "命令编码失败：\(error.localizedDescription)"
        }
    }

    func handleTouchOutput(_ output: TouchSurfaceOutput) {
        output.commands.forEach(send)

        guard let event = output.semanticEvent else { return }
        switch event {
        case let .tap(button):
            haptics.playTap()
            showHUD(tapHUDText(for: button))
        case .scrolling:
            break
        case let .dragStarted(button):
            haptics.playDragStart()
            showHUD(dragHUDText(for: button, state: .started))
        case .dragChanged:
            haptics.playDragTick()
        case let .dragEnded(button):
            showHUD(dragHUDText(for: button, state: .ended))
        }
    }

    func sendVideoAction(_ action: VideoActionKind) {
        send(.videoAction(action))
        haptics.playTap()
        showHUD(action.displayName)
    }

    func sendSystemAction(_ action: SystemActionKind) {
        send(.systemAction(action))
        haptics.playTap()
        showHUD(action.displayName)
    }

    func sendScreenGesture(
        kind: ScreenGestureKind,
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
        durationMs: Int
    ) {
        send(
            .screenGesture(
                kind: kind,
                startX: startX,
                startY: startY,
                endX: endX,
                endY: endY,
                durationMs: durationMs
            )
        )
        haptics.playTap()
        switch kind {
        case .tap:
            showHUD("点击")
        case .longPress:
            showHUD("长按")
        case .swipe:
            showHUD("滑动")
        case .unknown:
            showHUD("未知手势")
        }
    }

    private func bindDiscovery() {
        discoveryService.onUpdate = { [weak self] devices in
            guard let self else { return }
            Task { @MainActor in
                guard !self.isInBackground else { return }
                self.discoveredDevices = devices
                if self.activeEndpoint == nil, devices.isEmpty {
                    self.status = .discovering
                    self.statusMessage = "暂未发现桌面端，可手动输入地址"
                } else if self.activeEndpoint == nil {
                    self.status = .disconnected
                    self.statusMessage = "发现 \(devices.count) 台桌面端"
                }
            }
        }
    }

    private func bindTransport(_ transport: any RemoteTransporting, transportID: ObjectIdentifier) {
        transport.onStateChange = { [weak self] state in
            guard let self else { return }
            Task { @MainActor in
                guard self.activeTransportID == transportID else { return }
                self.handleTransportStateChange(state)
            }
        }

        transport.onMessage = { [weak self] message in
            guard let self else { return }
            Task { @MainActor in
                guard self.activeTransportID == transportID else { return }
                self.handleProtocolMessage(message)
            }
        }
    }

    private func handleTransportStateChange(_ state: TransportConnectionState) {
        switch state {
        case .idle:
            break
        case .connecting:
            status = activeEndpoint == nil ? .connecting : status
        case .connected:
            guard let activeEndpoint else { return }
            status = .connected
            route = .connected
            statusMessage = "已连接 \(activeEndpoint.displayName)"
            sendClientHello()
            registry.upsertRecent(activeEndpoint)
            registry.saveLastConnected(activeEndpoint)
            recentDevices = registry.loadRecentDevices()
            lastConnectedEndpoint = activeEndpoint
            haptics.playConnectionStateChange(success: true)
            showHUD("连接成功")
            startHeartbeat()
        case let .disconnected(errorDescription):
            heartbeatTask?.cancel()
            heartbeatTask = nil
            if let errorDescription, !errorDescription.isEmpty {
                errorMessage = errorDescription
            }
            status = .disconnected
            route = .onboarding
            statusMessage = "连接已断开"
        case let .failed(errorDescription):
            heartbeatTask?.cancel()
            heartbeatTask = nil
            errorMessage = errorDescription
            status = .failed
            route = .onboarding
            statusMessage = "连接失败"
            haptics.playConnectionStateChange(success: false)
        }
    }

    private func handleProtocolMessage(_ message: ProtocolMessage) {
        switch message {
        case .ack:
            statusMessage = "桌面端已确认连接"
        case let .status(message):
            statusMessage = message
        case .heartbeat:
            statusMessage = "桌面端在线"
        case let .unknown(type):
            statusMessage = "收到未识别消息：\(type)"
        }
    }

    private func handleSemanticUpdate(for command: RemoteCommand) {
        switch command {
        case .clientHello:
            break
        case .tap:
            break
        case .scroll:
            break
        case .move:
            break
        case let .drag(state, button, _, _):
            switch state {
            case .started:
                statusMessage = "\(button.displayName)拖拽已开始"
            case .changed:
                break
            case .ended:
                statusMessage = "\(button.displayName)拖拽已结束"
            }
        case let .videoAction(action):
            statusMessage = action.displayName
        case let .systemAction(action):
            statusMessage = action.displayName
        case let .screenGesture(kind, _, _, _, _, _):
            switch kind {
            case .tap:
                statusMessage = "屏幕点击"
            case .longPress:
                statusMessage = "屏幕长按"
            case .swipe:
                statusMessage = "屏幕滑动"
            case .unknown:
                break
            }
        case .heartbeat:
            break
        }
    }

    private func sendClientHello() {
        send(
            .clientHello(
                ClientHelloPayload(
                    clientId: registry.loadOrCreateClientID(),
                    displayName: UIDevice.current.name,
                    platform: "ios"
                )
            )
        )
    }

    private func tapHUDText(for button: MouseButtonKind) -> String {
        switch button {
        case .primary:
            return "左键点击"
        case .secondary:
            return "右键点击"
        case .middle:
            return "中键点击"
        }
    }

    private func dragHUDText(for button: MouseButtonKind, state: DragState) -> String {
        switch state {
        case .started:
            return "\(button.displayName)拖拽开始"
        case .changed:
            return "\(button.displayName)拖拽中"
        case .ended:
            return "\(button.displayName)拖拽结束"
        }
    }

    private func showHUD(_ message: String) {
        lastHUDMessage = message
        hudClearTask?.cancel()
        hudClearTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(1.2))
            guard !Task.isCancelled else { return }
            await MainActor.run {
                if self?.lastHUDMessage == message {
                    self?.lastHUDMessage = nil
                }
            }
        }
    }

    private func updateTouchSensitivity(_ settings: TouchSensitivitySettings) {
        let clamped = settings.clamped
        touchSensitivitySettings = clamped
        registry.saveTouchSensitivitySettings(clamped)
    }

    private func startHeartbeat() {
        heartbeatTask?.cancel()
        heartbeatTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(2))
                guard let self else { return }
                await MainActor.run {
                    self.send(.heartbeat)
                }
            }
        }
    }
}
