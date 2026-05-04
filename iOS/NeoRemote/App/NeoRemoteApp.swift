import SwiftUI

@main
struct NeoRemoteApp: App {
    @StateObject private var coordinator = SessionCoordinator()
    @Environment(\.scenePhase) private var scenePhase
    private let startsInDemo = ProcessInfo.processInfo.environment["NEOREMOTE_DEMO_MODE"] == "1"

    init() {
        ConnectionDiagnostics.resetIfEnabled()
    }

    var body: some Scene {
        WindowGroup {
            AppRootView(coordinator: coordinator)
                .task {
                    coordinator.start(startInDemo: startsInDemo)
                    if let endpoint = LaunchDiagnostics.autoconnectEndpoint {
                        ConnectionDiagnostics.log("launch-autoconnect host=\(endpoint.host) port=\(endpoint.port)")
                        try? await Task.sleep(for: .milliseconds(350))
                        coordinator.connect(to: endpoint)
                    }
                }
        }
        .onChange(of: scenePhase) { _, newPhase in
            switch newPhase {
            case .active:
                coordinator.handleAppDidBecomeActive()
            case .background:
                coordinator.handleAppDidEnterBackground()
            case .inactive:
                break
            @unknown default:
                coordinator.refreshDiscovery()
            }
        }
    }
}
