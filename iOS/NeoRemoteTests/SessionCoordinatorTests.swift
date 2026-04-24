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

    private func settleAsyncUpdates() async {
        await Task.yield()
        await Task.yield()
    }
}

private final class ControlledRemoteTransport: RemoteTransporting {
    var onStateChange: ((TransportConnectionState) -> Void)?
    var onMessage: ((ProtocolMessage) -> Void)?
    private(set) var sentPayloads: [Data] = []

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
