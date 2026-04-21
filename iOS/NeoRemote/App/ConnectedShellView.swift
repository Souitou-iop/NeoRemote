import SwiftUI

struct ConnectedShellView: View {
    enum Tab: Hashable {
        case remote
        case devices
        case settings
    }

    @ObservedObject var coordinator: SessionCoordinator
    @State private var selection: Tab = .remote

    var body: some View {
        TabView(selection: $selection) {
            RemoteView(coordinator: coordinator)
                .tabItem {
                    Label("Remote", systemImage: "hand.draw")
                }
                .tag(Tab.remote)

            DevicesView(coordinator: coordinator)
                .tabItem {
                    Label("Devices", systemImage: "desktopcomputer")
                }
                .tag(Tab.devices)

            SettingsView(coordinator: coordinator)
                .tabItem {
                    Label("Settings", systemImage: "gearshape")
                }
                .tag(Tab.settings)
        }
    }
}
