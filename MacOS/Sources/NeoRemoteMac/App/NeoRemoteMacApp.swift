import SwiftUI

@main
struct NeoRemoteMacApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var service = DesktopRemoteService()
    @Environment(\.openWindow) private var openWindow
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup("NeoRemote", id: "dashboard") {
            NavigationStack {
                DashboardView(service: service) {
                    openWindow(id: "settings")
                }
            }
            .task {
                service.bootstrap()
                appDelegate.configure(
                    service: service,
                    openDashboard: { openWindow(id: "dashboard") },
                    openSettings: { openWindow(id: "settings") }
                )
            }
        }
        .defaultSize(width: 860, height: 640)
        .windowResizability(.automatic)
        .commands {
            CommandGroup(replacing: .appSettings) {
                Button("设置…") {
                    openWindow(id: "settings")
                }
                .keyboardShortcut(",", modifiers: .command)
            }
        }
        .onChange(of: scenePhase) { _, newPhase in
            if newPhase == .active {
                service.applicationDidBecomeActive()
            }
        }

        Window("设置", id: "settings") {
            SettingsView(service: service)
        }
        .defaultSize(width: 780, height: 620)
        .windowResizability(.automatic)
    }
}
