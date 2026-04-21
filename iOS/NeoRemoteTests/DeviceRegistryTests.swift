import XCTest
@testable import NeoRemote

final class DeviceRegistryTests: XCTestCase {
    private var defaults: UserDefaults!
    private var registry: DeviceRegistry!

    override func setUp() {
        super.setUp()
        defaults = UserDefaults(suiteName: #file)!
        defaults.removePersistentDomain(forName: #file)
        registry = DeviceRegistry(defaults: defaults)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: #file)
        defaults = nil
        registry = nil
        super.tearDown()
    }

    func testUpsertRecentKeepsMostRecentEndpointFirstWithoutDuplicates() {
        let first = DesktopEndpoint(displayName: "Mac Studio", host: "192.168.0.2", port: 50505, source: .recent)
        let second = DesktopEndpoint(displayName: "Work PC", host: "192.168.0.3", port: 50505, source: .recent)

        registry.upsertRecent(first)
        registry.upsertRecent(second)
        registry.upsertRecent(first)

        let recents = registry.loadRecentDevices()

        XCTAssertEqual(recents.count, 2)
        XCTAssertEqual(recents.first?.host, "192.168.0.2")
    }

    func testValidateManualAddressRejectsInvalidInput() {
        XCTAssertThrowsError(try registry.validate(host: "", portText: "50505"))
        XCTAssertThrowsError(try registry.validate(host: "192.168.0.9", portText: "abc"))
    }

    func testSaveLastConnectedRoundTripsEndpoint() {
        let endpoint = DesktopEndpoint(displayName: "Desktop", host: "10.0.0.4", port: 50505, source: .manual)
        registry.saveLastConnected(endpoint)

        let loaded = registry.loadLastConnectedDevice()

        XCTAssertEqual(loaded?.host, endpoint.host)
        XCTAssertEqual(loaded?.port, endpoint.port)
    }
}
