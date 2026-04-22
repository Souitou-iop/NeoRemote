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

    func testMissingPermissionStillAllowsConnectionAndSendsStatus() async {
        let server = MockRemoteServer()
        let permissions = MockAccessibilityPermissionController(status: .denied)
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
        let endpoint = RemoteClientEndpoint(host: "192.168.1.8", port: 60000)
        server.emit(.listenerReady(port: 50505))
        server.emit(.clientConnected(clientID, endpoint))
        await flushEvents()

        XCTAssertEqual(service.dashboardState, .missingAccessibilityPermission)
        XCTAssertEqual(server.sentMessages.map(\.message), [.ack, .status("已连接，但缺少辅助功能权限，暂不可控制")])
        XCTAssertTrue(injector.commands.isEmpty)
    }

    func testOccupiedStateAppearsWhenSecondConnectionIsRejected() async {
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
        let active = RemoteClientEndpoint(host: "10.0.0.2", port: 55000)
        server.emit(.listenerReady(port: 50505))
        server.emit(.clientConnected(UUID(), active))
        server.emit(.clientRejected(RemoteClientEndpoint(host: "10.0.0.3", port: 55001), reason: "当前 Mac 正在被其他设备控制"))
        await flushEvents()

        XCTAssertEqual(service.dashboardState, .occupied(activeEndpoint: active))
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
        server.emit(.command(clientID, .move(dx: 10, dy: 5)))
        await flushEvents()

        XCTAssertEqual(injector.commands, [.move(dx: 10, dy: 5)])
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

    private func flushEvents() async {
        await Task.yield()
        await Task.yield()
    }
}

private final class MockRemoteServer: RemoteServering {
    struct SentRecord: Equatable {
        let message: ProtocolMessage
        let clientID: UUID
    }

    var onEvent: ((RemoteServerEvent) -> Void)?
    private(set) var sentMessages: [SentRecord] = []
    private(set) var startCallCount = 0
    private(set) var stopCallCount = 0

    func start(port: UInt16) throws { startCallCount += 1 }
    func stop() { stopCallCount += 1 }

    func send(_ message: ProtocolMessage, to clientID: UUID) {
        sentMessages.append(SentRecord(message: message, clientID: clientID))
    }

    func disconnect(clientID: UUID) {}

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

private final class RecordingInjector: RemoteCommandInjecting {
    private(set) var commands: [RemoteCommand] = []

    func handle(_ command: RemoteCommand) throws {
        commands.append(command)
    }
}

private final class RecordingFeedbackPlayer: ListeningFeedbackPlaying {
    private(set) var enabledTransitions: [Bool] = []

    func play(enabled: Bool) {
        enabledTransitions.append(enabled)
    }
}
