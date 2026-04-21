import SwiftUI

@main
struct NeoRemoteApp: App {
    @StateObject private var coordinator = SessionCoordinator()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            AppRootView(coordinator: coordinator)
                .task {
                    coordinator.start()
                }
        }
        .onChange(of: scenePhase) { _, newPhase in
            if newPhase == .active {
                coordinator.refreshDiscovery()
            }
        }
    }
}
