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

    func testStartWithoutRecoveryStaysOnOnboarding() {
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

        XCTAssertEqual(coordinator.route, .onboarding)
        XCTAssertEqual(coordinator.discoveredDevices.count, 1)
    }

    func testRecoveryConnectionPromotesRouteToConnected() {
        let saved = DesktopEndpoint(displayName: "Office Desktop", host: "10.0.0.8", port: 50505, source: .recent)
        registry.saveLastConnected(saved)

        let coordinator = SessionCoordinator(
            registry: registry,
            discoveryService: MockDiscoveryService(),
            transportFactory: { MockRemoteTransport() }
        )

        coordinator.start()

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
}
