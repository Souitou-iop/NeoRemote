import XCTest
@testable import NeoRemote

@MainActor
final class SessionCoordinatorTests: XCTestCase {
    private var defaults: UserDefaults!
    private var registry: DeviceRegistry!

    override func setUp() {
        super.setUp()
        defaults = UserDefaults(suiteName: "SessionCoordinatorTests")!
        defaults.removePersistentDomain(forName: "SessionCoordinatorTests")
        registry = DeviceRegistry(defaults: defaults)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: "SessionCoordinatorTests")
        defaults = nil
        registry = nil
        super.tearDown()
    }

    func testStartWithoutRecoveryStaysOnOnboarding() async {
        let discovery = MockDiscoveryService(
            cannedResults: [
                DesktopEndpoint(displayName: "Mac mini", host: "192.168.1.2", port: 50505, source: .discovered)
            ]
        )
        let coordinator = SessionCoordinator(
            registry: registry,
            discoveryService: discovery,
            transportFactory: { MockRemoteTransport() }
        )

        coordinator.start()
        await settleAsyncUpdates()

        XCTAssertEqual(coordinator.route, .onboarding)
        XCTAssertEqual(coordinator.discoveredDevices.count, 1)
    }

    func testRecoveryConnectionPromotesRouteToConnected() async {
        let saved = DesktopEndpoint(displayName: "Office Desktop", host: "10.0.0.8", port: 50505, source: .recent)
        registry.saveLastConnected(saved)

        let coordinator = SessionCoordinator(
            registry: registry,
            discoveryService: MockDiscoveryService(),
            transportFactory: { MockRemoteTransport() }
        )

        coordinator.start()
        await settleAsyncUpdates()

        XCTAssertEqual(coordinator.route, .connected)
        XCTAssertEqual(coordinator.status, .connected)
        XCTAssertEqual(coordinator.activeEndpoint?.host, saved.host)
    }

    func testManualConnectRejectsInvalidPort() {
        let coordinator = SessionCoordinator(
            registry: registry,
            discoveryService: MockDiscoveryService(),
            transportFactory: { MockRemoteTransport() }
        )
        coordinator.manualConnectDraft = ManualConnectDraft(host: "192.168.1.20", port: "bad")

        coordinator.connectUsingManualDraft()

        XCTAssertEqual(coordinator.route, .onboarding)
        XCTAssertEqual(coordinator.status, .failed)
        XCTAssertNotNil(coordinator.errorMessage)
    }

    func testManualAndroidConnectUsesAndroidPortAndPlatform() {
        let coordinator = SessionCoordinator(
            registry: registry,
            discoveryService: MockDiscoveryService(),
            transportFactory: { MockRemoteTransport() }
        )
        coordinator.manualConnectDraft = ManualConnectDraft(
            host: "192.168.1.88",
            port: "51101",
            target: .android
        )

        coordinator.connectUsingManualDraft()

        XCTAssertEqual(coordinator.activeEndpoint?.displayName, "Android")
        XCTAssertEqual(coordinator.activeEndpoint?.port, 51101)
        XCTAssertEqual(coordinator.activeEndpoint?.platform, .android)
        XCTAssertTrue(coordinator.isAndroidReceiverTarget)
    }

    func testWiredMacConnectUsesDefaultTcpPort() async {
        var transports: [ControlledRemoteTransport] = []
        let coordinator = SessionCoordinator(
            registry: registry,
            discoveryService: MockDiscoveryService(),
            transportFactory: {
                let transport = ControlledRemoteTransport()
                transports.append(transport)
                return transport
            }
        )

        coordinator.connectUsingWiredMacAddress(host: "172.20.10.2")
        transports.first?.emitState(.connected)
        await settleAsyncUpdates()

        XCTAssertEqual(coordinator.manualConnectDraft, ManualConnectDraft(host: "172.20.10.2", port: "50505"))
        XCTAssertEqual(coordinator.activeEndpoint?.host, "172.20.10.2")
        XCTAssertEqual(coordinator.activeEndpoint?.port, 50505)
        XCTAssertEqual(coordinator.activeEndpoint?.displayName, "Desktop")
        XCTAssertEqual(coordinator.status, .connected)
    }

    func testStaleTransportCallbacksDoNotOverrideCurrentConnection() async {
        var transports: [ControlledRemoteTransport] = []
        let coordinator = SessionCoordinator(
            registry: registry,
            discoveryService: MockDiscoveryService(),
            transportFactory: {
                let transport = ControlledRemoteTransport()
                transports.append(transport)
                return transport
            }
        )

        let oldEndpoint = DesktopEndpoint(displayName: "Old Desktop", host: "10.0.0.8", port: 50505, source: .manual)
        let currentEndpoint = DesktopEndpoint(displayName: "Current Desktop", host: "10.0.0.9", port: 50505, source: .manual)

        coordinator.connect(to: oldEndpoint)
        let firstTransport = try! XCTUnwrap(transports.first)
        firstTransport.emitState(.connected)
        await settleAsyncUpdates()

        coordinator.connect(to: currentEndpoint)
        let secondTransport = try! XCTUnwrap(transports.last)
        secondTransport.emitState(.connected)
        firstTransport.emitState(.disconnected(errorDescription: "旧连接关闭"))
        firstTransport.emitMessage(.status("旧连接消息"))
        await settleAsyncUpdates()

        XCTAssertEqual(coordinator.route, .connected)
        XCTAssertEqual(coordinator.status, .connected)
        XCTAssertEqual(coordinator.activeEndpoint?.host, currentEndpoint.host)
        XCTAssertEqual(coordinator.statusMessage, "已连接 \(currentEndpoint.displayName)")
    }

    func testRefreshAfterConnectionFailureClearsStaleErrorAndScansAgain() async {
        var transports: [ControlledRemoteTransport] = []
        let coordinator = SessionCoordinator(
            registry: registry,
            discoveryService: MockDiscoveryService(),
            transportFactory: {
                let transport = ControlledRemoteTransport()
                transports.append(transport)
                return transport
            }
        )

        coordinator.connect(to: DesktopEndpoint(displayName: "Offline Mac", host: "10.0.0.99", port: 50505, source: .manual))
        transports.first?.emitState(.failed(errorDescription: "Network.NWError error 53"))
        await settleAsyncUpdates()

        coordinator.refreshDiscovery()
        await settleAsyncUpdates()

        XCTAssertNil(coordinator.errorMessage)
        XCTAssertNil(coordinator.activeEndpoint)
        XCTAssertEqual(coordinator.status, .discovering)
        XCTAssertEqual(coordinator.statusMessage, "暂未发现设备，可手动输入地址")
    }

    func testTcpTransportConnectDoesNotEmitSyntheticDisconnectBeforeConnecting() {
        let transport = TCPRemoteTransport()
        defer { transport.disconnect() }
        var states: [TransportConnectionState] = []
        transport.onStateChange = { states.append($0) }

        transport.connect(
            to: DesktopEndpoint(
                displayName: "Android",
                host: "192.0.2.1",
                port: 51101,
                platform: .android,
                source: .manual
            )
        )

        XCTAssertEqual(states.first, .connecting)
        XCTAssertFalse(states.contains(.disconnected(errorDescription: nil)))
    }

    func testBackgroundStopsDiscoveryWithoutPublishingEmptyState() async {
        let discovery = TrackingDiscoveryService(
            cannedResults: [
                DesktopEndpoint(displayName: "Mac mini", host: "192.168.1.2", port: 50505, source: .discovered)
            ]
        )
        let coordinator = SessionCoordinator(
            registry: registry,
            discoveryService: discovery,
            transportFactory: { MockRemoteTransport() }
        )

        coordinator.start()
        await settleAsyncUpdates()

        XCTAssertEqual(coordinator.discoveredDevices.count, 1)

        coordinator.handleAppDidEnterBackground()
        await settleAsyncUpdates()

        XCTAssertEqual(discovery.stopCount, 1)
        XCTAssertEqual(coordinator.discoveredDevices.count, 1)

        coordinator.handleAppDidBecomeActive()
        await settleAsyncUpdates()

        XCTAssertEqual(discovery.refreshCount, 2)
        XCTAssertEqual(coordinator.discoveredDevices.count, 1)
    }

    func testConnectedLifecyclePausesAndRestartsHeartbeat() async {
        var transports: [ControlledRemoteTransport] = []
        let coordinator = SessionCoordinator(
            registry: registry,
            discoveryService: TrackingDiscoveryService(),
            transportFactory: {
                let transport = ControlledRemoteTransport()
                transports.append(transport)
                return transport
            }
        )

        coordinator.connect(to: DesktopEndpoint(displayName: "Desk", host: "10.0.0.2", port: 50505, source: .manual))
        let transport = try! XCTUnwrap(transports.first)
        transport.emitState(.connected)
        await settleAsyncUpdates()

        XCTAssertEqual(coordinator.status, .connected)

        coordinator.handleAppDidEnterBackground()
        coordinator.handleAppDidBecomeActive()
        try? await Task.sleep(for: .milliseconds(2100))

        XCTAssertFalse(transport.sentPayloads.isEmpty)
    }

    func testTemporaryControlModeDoesNotUpdateDefaultControlMode() {
        let coordinator = SessionCoordinator(
            registry: registry,
            discoveryService: MockDiscoveryService(),
            transportFactory: { MockRemoteTransport() }
        )

        coordinator.setControlMode(.shortVideo)

        XCTAssertEqual(coordinator.controlMode, .shortVideo)
        XCTAssertEqual(coordinator.defaultControlMode, .screenControl)
        XCTAssertEqual(registry.loadControlMode(), .screenControl)
    }

    func testAndroidReceiverTargetFlagOnlyEnablesAndroidSpecificControls() async {
        var transports: [ControlledRemoteTransport] = []
        let coordinator = SessionCoordinator(
            registry: registry,
            discoveryService: MockDiscoveryService(),
            transportFactory: {
                let transport = ControlledRemoteTransport()
                transports.append(transport)
                return transport
            }
        )

        coordinator.connect(
            to: DesktopEndpoint(
                displayName: "Mac Studio",
                host: "10.0.0.10",
                port: 50505,
                platform: .macOS,
                source: .manual
            )
        )
        transports.last?.emitState(.connected)
        await settleAsyncUpdates()

        XCTAssertFalse(coordinator.isAndroidReceiverTarget)

        coordinator.connect(
            to: DesktopEndpoint(
                displayName: "Android Phone",
                host: "10.0.0.20",
                port: 51101,
                platform: .android,
                source: .manual
            )
        )
        transports.last?.emitState(.connected)
        await settleAsyncUpdates()

        XCTAssertTrue(coordinator.isAndroidReceiverTarget)
    }

    func testSwitchingToShortVideoOnAndroidReceiverDoesNotRequestVideoState() async throws {
        var transports: [ControlledRemoteTransport] = []
        let coordinator = SessionCoordinator(
            registry: registry,
            discoveryService: MockDiscoveryService(),
            transportFactory: {
                let transport = ControlledRemoteTransport()
                transports.append(transport)
                return transport
            }
        )
        let endpoint = DesktopEndpoint(
            displayName: "Android Phone",
            host: "10.0.0.20",
            port: 51101,
            platform: .android,
            source: .manual
        )

        coordinator.connect(to: endpoint)
        let transport = try XCTUnwrap(transports.first)
        transport.emitState(.connected)
        await settleAsyncUpdates()
        transport.sentPayloads.removeAll()

        coordinator.setControlMode(.shortVideo)
        await settleAsyncUpdates()

        let commandTypes = try transport.sentPayloads.map { payload in
            try XCTUnwrap(JSONSerialization.jsonObject(with: payload) as? [String: Any])["type"] as? String
        }
        XCTAssertFalse(commandTypes.contains("videoStateRequest"))
    }

    func testVideoStateMessageDoesNotChangeInteractionState() async {
        var transports: [ControlledRemoteTransport] = []
        let coordinator = SessionCoordinator(
            registry: registry,
            discoveryService: MockDiscoveryService(),
            transportFactory: {
                let transport = ControlledRemoteTransport()
                transports.append(transport)
                return transport
            }
        )

        coordinator.connect(
            to: DesktopEndpoint(
                displayName: "Android Phone",
                host: "10.0.0.20",
                port: 51101,
                platform: .android,
                source: .manual
            )
        )
        transports.first?.emitState(.connected)
        await settleAsyncUpdates()
        transports.first?.emitMessage(
            .videoState(
                VideoInteractionState(
                    targetPackage: "com.ss.android.ugc.aweme",
                    likeState: .active,
                    favoriteState: .inactive
                )
            )
        )
        await settleAsyncUpdates()

        XCTAssertEqual(coordinator.videoInteractionState.likeState, .unknown)
        XCTAssertEqual(coordinator.videoInteractionState.favoriteState, .unknown)
    }

    func testUnknownVideoStateDoesNotOverrideStatusMessage() async {
        var transports: [ControlledRemoteTransport] = []
        let coordinator = SessionCoordinator(
            registry: registry,
            discoveryService: MockDiscoveryService(),
            transportFactory: {
                let transport = ControlledRemoteTransport()
                transports.append(transport)
                return transport
            }
        )

        coordinator.connect(
            to: DesktopEndpoint(
                displayName: "Android Phone",
                host: "10.0.0.20",
                port: 51101,
                platform: .android,
                source: .manual
            )
        )
        transports.first?.emitState(.connected)
        await settleAsyncUpdates()
        let connectedStatus = coordinator.statusMessage
        transports.first?.emitMessage(
            .videoState(
                VideoInteractionState(
                    targetPackage: "com.ss.android.ugc.aweme",
                    likeState: .unknown,
                    favoriteState: .unknown
                )
            )
        )
        await settleAsyncUpdates()

        XCTAssertEqual(coordinator.statusMessage, connectedStatus)
    }

    func testDefaultControlModePersistsWithoutChangingCurrentMode() {
        let coordinator = SessionCoordinator(
            registry: registry,
            discoveryService: MockDiscoveryService(),
            transportFactory: { MockRemoteTransport() }
        )

        coordinator.setDefaultControlMode(.shortVideo)

        XCTAssertEqual(coordinator.controlMode, .screenControl)
        XCTAssertEqual(coordinator.defaultControlMode, .shortVideo)
        XCTAssertEqual(registry.loadControlMode(), .shortVideo)
    }

    private func settleAsyncUpdates() async {
        await Task.yield()
        await Task.yield()
    }
}

private final class ControlledRemoteTransport: RemoteTransporting {
    var onStateChange: ((TransportConnectionState) -> Void)?
    var onMessage: ((ProtocolMessage) -> Void)?
    var sentPayloads: [Data] = []

    func connect(to _: DesktopEndpoint) {}

    func disconnect() {}

    func send(_ data: Data) {
        sentPayloads.append(data)
    }

    func emitState(_ state: TransportConnectionState) {
        onStateChange?(state)
    }

    func emitMessage(_ message: ProtocolMessage) {
        onMessage?(message)
    }
}

private final class TrackingDiscoveryService: DiscoveryServing {
    var onUpdate: (([DesktopEndpoint]) -> Void)?
    var cannedResults: [DesktopEndpoint]
    private(set) var refreshCount = 0
    private(set) var stopCount = 0

    init(cannedResults: [DesktopEndpoint] = []) {
        self.cannedResults = cannedResults
    }

    func start() {
        refresh()
    }

    func stop() {
        stopCount += 1
        onUpdate?([])
    }

    func refresh() {
        refreshCount += 1
        onUpdate?(cannedResults)
    }
}
