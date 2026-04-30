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

    func testUpsertRecentMergesSameDesktopAcrossBonjourAndIPAddress() {
        let bonjour = DesktopEndpoint(
            displayName: "Ebato的 Mac mini",
            host: "77db8123-21bc-4729-be20-875c7365bc3d.local",
            port: 50505,
            platform: .macOS,
            source: .recent
        )
        let ipAddress = DesktopEndpoint(
            displayName: "Ebato的 Mac mini",
            host: "192.168.31.35",
            port: 50505,
            platform: .windows,
            source: .discovered
        )

        registry.upsertRecent(bonjour)
        registry.upsertRecent(ipAddress)

        let recents = registry.loadRecentDevices()

        XCTAssertEqual(recents.count, 1)
        XCTAssertEqual(recents.first?.host, "192.168.31.35")
    }

    func testLoadRecentDevicesCompactsLegacyDuplicatesAndLimitsCount() throws {
        let legacyDevices = [
            DesktopEndpoint(displayName: "Mac A", host: "a.local", port: 50505, platform: .macOS, lastSeenAt: Date(timeIntervalSince1970: 1), source: .recent),
            DesktopEndpoint(displayName: "Mac A", host: "192.168.1.2", port: 50505, platform: .macOS, lastSeenAt: Date(timeIntervalSince1970: 5), source: .recent),
            DesktopEndpoint(displayName: "Mac B", host: "192.168.1.3", port: 50505, platform: .macOS, lastSeenAt: Date(timeIntervalSince1970: 4), source: .recent),
            DesktopEndpoint(displayName: "Mac C", host: "192.168.1.4", port: 50505, platform: .macOS, lastSeenAt: Date(timeIntervalSince1970: 3), source: .recent),
            DesktopEndpoint(displayName: "Mac D", host: "192.168.1.5", port: 50505, platform: .macOS, lastSeenAt: Date(timeIntervalSince1970: 2), source: .recent),
        ]
        let data = try JSONEncoder().encode(legacyDevices)
        defaults.set(data, forKey: "recent_devices")

        let recents = registry.loadRecentDevices()

        XCTAssertEqual(recents.map(\.displayName), ["Mac A", "Mac B", "Mac C"])
        XCTAssertEqual(recents.first?.host, "192.168.1.2")
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

    func testHapticsEnabledDefaultsToTrue() {
        XCTAssertTrue(registry.loadHapticsEnabled())
    }

    func testSaveHapticsEnabledRoundTripsValue() {
        registry.saveHapticsEnabled(false)

        XCTAssertFalse(registry.loadHapticsEnabled())
    }

    func testTouchSensitivitySettingsDefaultAndRoundTrip() {
        XCTAssertEqual(registry.loadTouchSensitivitySettings(), .default)

        registry.saveTouchSensitivitySettings(
            TouchSensitivitySettings(cursorSensitivity: 1.7, swipeSensitivity: 0.8)
        )

        XCTAssertEqual(
            registry.loadTouchSensitivitySettings(),
            TouchSensitivitySettings(cursorSensitivity: 1.7, swipeSensitivity: 0.8)
        )
    }

    func testTouchSensitivitySettingsAreClamped() {
        registry.saveTouchSensitivitySettings(
            TouchSensitivitySettings(cursorSensitivity: 99, swipeSensitivity: 0.1)
        )

        XCTAssertEqual(
            registry.loadTouchSensitivitySettings(),
            TouchSensitivitySettings(cursorSensitivity: 2.5, swipeSensitivity: 0.5)
        )
    }

    func testControlModeDefaultsToScreenControlAndRoundTrips() {
        XCTAssertEqual(registry.loadControlMode(), .screenControl)

        registry.saveControlMode(.shortVideo)

        XCTAssertEqual(registry.loadControlMode(), .shortVideo)
    }
}
