import Foundation
import XCTest
@testable import NeoRemoteMac

@MainActor
final class DesktopRemoteServiceTests: XCTestCase {
    func testFirstLaunchStateBeforeOnboardingCompletion() {
        let server = MockRemoteServer()
        let permissions = MockAccessibilityPermissionController(status: .denied)
        let injector = RecordingInjector()
        let feedback = RecordingFeedbackPlayer()
        let preferences = AppPreferences(defaults: UserDefaults(suiteName: #function)!)

        let service = DesktopRemoteService(
            server: server,
            permissionController: permissions,
            injector: injector,
            preferences: preferences,
            feedbackPlayer: feedback
        )

        service.bootstrap()

        XCTAssertEqual(service.dashboardState, .firstLaunch)
    }

    func testMissingPermissionSendsStatusAfterApproval() async {
        let server = MockRemoteServer()
        let permissions = MockAccessibilityPermissionController(status: .denied)
        let injector = RecordingInjector()
        let feedback = RecordingFeedbackPlayer()
        let preferences = AppPreferences(defaults: UserDefaults(suiteName: #function)!)
        preferences.didCompleteOnboarding = true
        preferences.trustedDeviceIDs = []

        let service = DesktopRemoteService(
            server: server,
            permissionController: permissions,
            injector: injector,
            preferences: preferences,
            feedbackPlayer: feedback
        )

        service.bootstrap()
        let clientID = UUID()
        let endpoint = RemoteClientEndpoint(host: "192.168.1.8", port: 60000)
        server.emit(.listenerReady(port: 50505))
        server.emit(.clientConnected(clientID, endpoint))
        await flushEvents()
        service.approveConnection(requestID: clientID)
        await flushEvents()

        XCTAssertEqual(service.dashboardState, .missingAccessibilityPermission)
        XCTAssertEqual(server.sentMessages.map(\.message), [.status("正在等待 Mac 允许连接"), .ack, .status("已连接，但缺少辅助功能权限，暂不可控制")])
        XCTAssertTrue(injector.commands.isEmpty)
    }

    func testMultipleConnectionsCanBeApprovedTogether() async {
        let server = MockRemoteServer()
        let permissions = MockAccessibilityPermissionController(status: .granted)
        let injector = RecordingInjector()
        let feedback = RecordingFeedbackPlayer()
        let preferences = AppPreferences(defaults: UserDefaults(suiteName: #function)!)
        preferences.didCompleteOnboarding = true

        let service = DesktopRemoteService(
            server: server,
            permissionController: permissions,
            injector: injector,
            preferences: preferences,
            feedbackPlayer: feedback
        )

        service.bootstrap()
        let firstID = UUID()
        let secondID = UUID()
        let first = RemoteClientEndpoint(host: "10.0.0.2", port: 55000)
        let second = RemoteClientEndpoint(host: "10.0.0.3", port: 55001)
        server.emit(.listenerReady(port: 50505))
        server.emit(.clientConnected(firstID, first))
        server.emit(.clientConnected(secondID, second))
        await flushEvents()
        service.approveConnection(requestID: firstID)
        service.approveConnection(requestID: secondID)
        await flushEvents()

        XCTAssertEqual(service.connectedClients.map(\.endpoint), [first, second])
        XCTAssertEqual(service.dashboardState, .connected(second))
    }

    func testDisconnectReturnsToIdleListening() async {
        let server = MockRemoteServer()
        let permissions = MockAccessibilityPermissionController(status: .granted)
        let injector = RecordingInjector()
        let feedback = RecordingFeedbackPlayer()
        let preferences = AppPreferences(defaults: UserDefaults(suiteName: #function)!)
        preferences.didCompleteOnboarding = true

        let service = DesktopRemoteService(
            server: server,
            permissionController: permissions,
            injector: injector,
            preferences: preferences,
            feedbackPlayer: feedback
        )

        service.bootstrap()
        let clientID = UUID()
        let endpoint = RemoteClientEndpoint(host: "10.0.0.2", port: 55000)
        server.emit(.listenerReady(port: 50505))
        server.emit(.clientConnected(clientID, endpoint))
        await flushEvents()
        service.approveConnection(requestID: clientID)
        server.emit(.clientDisconnected(clientID, endpoint, errorDescription: nil))
        await flushEvents()

        XCTAssertEqual(service.dashboardState, .idleListening)
    }

    func testGrantedPermissionAllowsCommandInjection() async {
        let server = MockRemoteServer()
        let permissions = MockAccessibilityPermissionController(status: .granted)
        let injector = RecordingInjector()
        let feedback = RecordingFeedbackPlayer()
        let preferences = AppPreferences(defaults: UserDefaults(suiteName: #function)!)
        preferences.didCompleteOnboarding = true

        let service = DesktopRemoteService(
            server: server,
            permissionController: permissions,
            injector: injector,
            preferences: preferences,
            feedbackPlayer: feedback
        )

        service.bootstrap()
        let clientID = UUID()
        let endpoint = RemoteClientEndpoint(host: "10.0.0.2", port: 55000)
        server.emit(.listenerReady(port: 50505))
        server.emit(.clientConnected(clientID, endpoint))
        await flushEvents()
        service.approveConnection(requestID: clientID)
        server.emit(.command(clientID, .move(dx: 10, dy: 5)))
        await flushEvents()
        await waitUntil { injector.commands == [.move(dx: 10, dy: 5)] }

        XCTAssertEqual(injector.commands, [.move(dx: 10, dy: 5)])
    }

    func testClientHelloUpdatesPendingRequestAndTrustedAutoAllow() async {
        let server = MockRemoteServer()
        let permissions = MockAccessibilityPermissionController(status: .granted)
        let injector = RecordingInjector()
        let feedback = RecordingFeedbackPlayer()
        let preferences = AppPreferences(defaults: UserDefaults(suiteName: #function)!)
        preferences.didCompleteOnboarding = true
        preferences.trustedDeviceIDs = ["ios-client-1"]

        let service = DesktopRemoteService(
            server: server,
            permissionController: permissions,
            injector: injector,
            preferences: preferences,
            feedbackPlayer: feedback
        )

        service.bootstrap()
        let clientID = UUID()
        let endpoint = RemoteClientEndpoint(host: "10.0.0.4", port: 55002)
        server.emit(.listenerReady(port: 50505))
        server.emit(.clientConnected(clientID, endpoint))
        server.emit(.command(clientID, .clientHello(ClientHelloPayload(clientId: "ios-client-1", displayName: "Ebato 的 iPhone", platform: "ios"))))
        await flushEvents()

        XCTAssertTrue(service.pendingConnectionRequests.isEmpty)
        XCTAssertEqual(service.connectedClients.first?.displayName, "Ebato 的 iPhone")
        XCTAssertEqual(service.connectedClients.first?.platform, "ios")
        XCTAssertEqual(server.sentMessages.map(\.message).suffix(2), [.ack, .status("已连接 Mac，可开始控制")])
    }

    func testRejectConnectionDisconnectsOnlyThatRequest() async {
        let server = MockRemoteServer()
        let permissions = MockAccessibilityPermissionController(status: .granted)
        let injector = RecordingInjector()
        let feedback = RecordingFeedbackPlayer()
        let preferences = AppPreferences(defaults: UserDefaults(suiteName: #function)!)
        preferences.didCompleteOnboarding = true

        let service = DesktopRemoteService(
            server: server,
            permissionController: permissions,
            injector: injector,
            preferences: preferences,
            feedbackPlayer: feedback
        )

        service.bootstrap()
        let rejectedID = UUID()
        let keptID = UUID()
        server.emit(.listenerReady(port: 50505))
        server.emit(.clientConnected(rejectedID, RemoteClientEndpoint(host: "10.0.0.5", port: 55003)))
        server.emit(.clientConnected(keptID, RemoteClientEndpoint(host: "10.0.0.6", port: 55004)))
        await flushEvents()
        service.rejectConnection(requestID: rejectedID)
        await flushEvents()

        XCTAssertEqual(server.disconnectedClientIDs, [rejectedID])
        XCTAssertEqual(service.pendingConnectionRequests.map(\.id), [keptID])
    }

    func testBootstrapRespectsPersistedListeningDisabledState() {
        let server = MockRemoteServer()
        let permissions = MockAccessibilityPermissionController(status: .granted)
        let injector = RecordingInjector()
        let feedback = RecordingFeedbackPlayer()
        let preferences = AppPreferences(defaults: UserDefaults(suiteName: #function)!)
        preferences.didCompleteOnboarding = true
        preferences.isListeningEnabled = false

        let service = DesktopRemoteService(
            server: server,
            permissionController: permissions,
            injector: injector,
            preferences: preferences,
            feedbackPlayer: feedback
        )

        service.bootstrap()

        XCTAssertEqual(service.dashboardState, .listeningDisabled)
        XCTAssertFalse(service.isListeningEnabled)
        XCTAssertEqual(server.startCallCount, 0)
    }

    func testRepeatedStartWhileListenerIsStartingDoesNotRestartServer() {
        let server = MockRemoteServer()
        let permissions = MockAccessibilityPermissionController(status: .granted)
        let injector = RecordingInjector()
        let feedback = RecordingFeedbackPlayer()
        let preferences = AppPreferences(defaults: UserDefaults(suiteName: #function)!)
        preferences.didCompleteOnboarding = true

        let service = DesktopRemoteService(
            server: server,
            permissionController: permissions,
            injector: injector,
            preferences: preferences,
            feedbackPlayer: feedback
        )

        service.bootstrap()
        service.startListening()

        XCTAssertEqual(server.startCallCount, 1)
        XCTAssertEqual(service.dashboardState, .idleListening)
    }

    func testDisablingListeningPersistsStateAndTriggersFeedback() {
        let server = MockRemoteServer()
        let permissions = MockAccessibilityPermissionController(status: .granted)
        let injector = RecordingInjector()
        let feedback = RecordingFeedbackPlayer()
        let defaults = UserDefaults(suiteName: #function)!
        let preferences = AppPreferences(defaults: defaults)
        preferences.didCompleteOnboarding = true
        preferences.isListeningEnabled = true
        preferences.isListeningSoundEnabled = true

        let service = DesktopRemoteService(
            server: server,
            permissionController: permissions,
            injector: injector,
            preferences: preferences,
            feedbackPlayer: feedback
        )

        service.bootstrap()
        service.setListeningEnabled(false)

        XCTAssertEqual(service.dashboardState, .listeningDisabled)
        XCTAssertFalse(preferences.isListeningEnabled)
        XCTAssertEqual(feedback.enabledTransitions, [false])
        XCTAssertEqual(server.stopCallCount, 1)
    }

    func testListeningSoundPreferencePersistsAndSuppressesFeedback() {
        let server = MockRemoteServer()
        let permissions = MockAccessibilityPermissionController(status: .granted)
        let injector = RecordingInjector()
        let feedback = RecordingFeedbackPlayer()
        let defaults = UserDefaults(suiteName: #function)!
        let preferences = AppPreferences(defaults: defaults)
        preferences.didCompleteOnboarding = true
        preferences.isListeningEnabled = true
        preferences.isListeningSoundEnabled = true

        let service = DesktopRemoteService(
            server: server,
            permissionController: permissions,
            injector: injector,
            preferences: preferences,
            feedbackPlayer: feedback
        )

        service.bootstrap()
        service.setListeningSoundEnabled(false)
        service.setListeningEnabled(false)

        XCTAssertFalse(service.isListeningSoundEnabled)
        XCTAssertFalse(preferences.isListeningSoundEnabled)
        XCTAssertTrue(feedback.enabledTransitions.isEmpty)
    }

    func testMenuBarVisibilityPreferencePersists() {
        let server = MockRemoteServer()
        let permissions = MockAccessibilityPermissionController(status: .granted)
        let injector = RecordingInjector()
        let feedback = RecordingFeedbackPlayer()
        let defaults = UserDefaults(suiteName: #function)!
        let preferences = AppPreferences(defaults: defaults)
        preferences.didCompleteOnboarding = true
        preferences.showsMenuBarExtra = false

        let service = DesktopRemoteService(
            server: server,
            permissionController: permissions,
            injector: injector,
            preferences: preferences,
            feedbackPlayer: feedback
        )

        service.bootstrap()

        XCTAssertFalse(service.showsMenuBarExtra)

        service.setMenuBarVisibility(true)

        XCTAssertTrue(service.showsMenuBarExtra)
        XCTAssertTrue(preferences.showsMenuBarExtra)
    }

    func testRedundantMenuBarVisibilityUpdateDoesNotAppendEvent() {
        let server = MockRemoteServer()
        let permissions = MockAccessibilityPermissionController(status: .granted)
        let injector = RecordingInjector()
        let feedback = RecordingFeedbackPlayer()
        let defaults = UserDefaults(suiteName: #function)!
        let preferences = AppPreferences(defaults: defaults)
        preferences.didCompleteOnboarding = true
        preferences.showsMenuBarExtra = true

        let service = DesktopRemoteService(
            server: server,
            permissionController: permissions,
            injector: injector,
            preferences: preferences,
            feedbackPlayer: feedback
        )

        service.bootstrap()
        let baselineCount = service.recentEvents.count

        service.setMenuBarVisibility(true)

        XCTAssertEqual(service.recentEvents.count, baselineCount)
        XCTAssertTrue(service.showsMenuBarExtra)
    }

    func testMenuBarSymbolUsesBoltToggleStates() {
        let server = MockRemoteServer()
        let permissions = MockAccessibilityPermissionController(status: .granted)
        let injector = RecordingInjector()
        let feedback = RecordingFeedbackPlayer()
        let defaults = UserDefaults(suiteName: #function)!
        let preferences = AppPreferences(defaults: defaults)
        preferences.didCompleteOnboarding = true
        preferences.isListeningEnabled = true

        let service = DesktopRemoteService(
            server: server,
            permissionController: permissions,
            injector: injector,
            preferences: preferences,
            feedbackPlayer: feedback
        )

        service.bootstrap()
        XCTAssertEqual(service.menuBarSymbolName, "bolt.horizontal.fill")

        service.setListeningEnabled(false)
        XCTAssertEqual(service.menuBarSymbolName, "bolt.horizontal")
    }

    func testConnectionAddressProviderFiltersLoopbackAndRecommendsIPhoneWiredSubnet() {
        let addresses = SystemDesktopConnectionAddressProvider.makeAddresses(
            from: [
                .init(name: "lo0", host: "127.0.0.1", isUp: true, isLoopback: true),
                .init(name: "en0", host: "192.168.1.12", isUp: true, isLoopback: false),
                .init(name: "bridge100", host: "172.20.10.2", isUp: true, isLoopback: false),
                .init(name: "down0", host: "10.0.0.9", isUp: false, isLoopback: false),
            ],
            port: 50505
        )

        XCTAssertEqual(addresses.map(\.host), ["172.20.10.2", "192.168.1.12"])
        XCTAssertTrue(addresses[0].isRecommendedForWired)
        XCTAssertEqual(addresses[0].label, "iPhone USB / 个人热点")
        XCTAssertEqual(addresses[0].addressText, "172.20.10.2:50505")
    }

    func testServiceRefreshesConnectionAddressesFromProvider() {
        let server = MockRemoteServer()
        let permissions = MockAccessibilityPermissionController(status: .granted)
        let injector = RecordingInjector()
        let feedback = RecordingFeedbackPlayer()
        let preferences = AppPreferences(defaults: UserDefaults(suiteName: #function)!)
        let provider = MockConnectionAddressProvider(
            addresses: [
                DesktopConnectionAddress(
                    host: "172.20.10.2",
                    port: 50505,
                    label: "iPhone USB / 个人热点",
                    isRecommendedForWired: true
                )
            ]
        )

        let service = DesktopRemoteService(
            server: server,
            permissionController: permissions,
            injector: injector,
            preferences: preferences,
            feedbackPlayer: feedback,
            addressProvider: provider
        )

        service.refreshConnectionAddresses()

        XCTAssertEqual(service.connectionAddresses.first?.addressText, "172.20.10.2:50505")
        XCTAssertTrue(service.connectionAddresses.first?.isRecommendedForWired == true)
    }

    private func flushEvents() async {
        await Task.yield()
        await Task.yield()
    }

    private func waitUntil(
        timeout: Duration = .milliseconds(500),
        condition: @escaping () -> Bool
    ) async {
        let start = ContinuousClock.now
        while !condition(), ContinuousClock.now - start < timeout {
            try? await Task.sleep(for: .milliseconds(10))
        }
    }
}

private final class MockRemoteServer: RemoteServering {
    struct SentRecord: Equatable {
        let message: ProtocolMessage
        let clientID: UUID
    }

    var onEvent: ((RemoteServerEvent) -> Void)?
    private(set) var sentMessages: [SentRecord] = []
    private(set) var disconnectedClientIDs: [UUID] = []
    private(set) var startCallCount = 0
    private(set) var stopCallCount = 0

    func start(port: UInt16) throws { startCallCount += 1 }
    func stop() { stopCallCount += 1 }

    func send(_ message: ProtocolMessage, to clientID: UUID) {
        sentMessages.append(SentRecord(message: message, clientID: clientID))
    }

    func disconnect(clientID: UUID) {
        disconnectedClientIDs.append(clientID)
    }

    func emit(_ event: RemoteServerEvent) {
        onEvent?(event)
    }
}

private final class MockAccessibilityPermissionController: AccessibilityPermissionControlling {
    var currentStatus: AccessibilityPermissionStatus

    init(status: AccessibilityPermissionStatus) {
        currentStatus = status
    }

    var status: AccessibilityPermissionStatus { currentStatus }

    func refresh() -> AccessibilityPermissionStatus {
        currentStatus
    }

    func requestPrompt() -> AccessibilityPermissionStatus {
        currentStatus
    }

    func openSettings() {}
}

private final class RecordingInjector: RemoteCommandInjecting, @unchecked Sendable {
    private let lock = NSLock()
    private var storage: [RemoteCommand] = []

    var commands: [RemoteCommand] {
        lock.withLock { storage }
    }

    func handle(_ command: RemoteCommand) throws {
        lock.withLock {
            storage.append(command)
        }
    }
}

private final class RecordingFeedbackPlayer: ListeningFeedbackPlaying {
    private(set) var enabledTransitions: [Bool] = []

    func play(enabled: Bool) {
        enabledTransitions.append(enabled)
    }
}

private struct MockConnectionAddressProvider: DesktopConnectionAddressProviding {
    let addresses: [DesktopConnectionAddress]

    func loadConnectionAddresses(port _: UInt16) -> [DesktopConnectionAddress] {
        addresses
    }
}
