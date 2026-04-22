import SwiftUI

@main
struct NeoRemoteApp: App {
    @StateObject private var coordinator = SessionCoordinator()
    @Environment(\.scenePhase) private var scenePhase
    private let startsInDemo = ProcessInfo.processInfo.environment["NEOREMOTE_DEMO_MODE"] == "1"

    var body: some Scene {
        WindowGroup {
            AppRootView(coordinator: coordinator)
                .task {
                    coordinator.start(startInDemo: startsInDemo)
                }
        }
        .onChange(of: scenePhase) { _, newPhase in
            if newPhase == .active {
                coordinator.refreshDiscovery()
            }
        }
    }
}
