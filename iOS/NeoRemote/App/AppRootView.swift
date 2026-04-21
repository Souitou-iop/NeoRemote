import SwiftUI

struct AppRootView: View {
    @ObservedObject var coordinator: SessionCoordinator

    var body: some View {
        Group {
            switch coordinator.route {
            case .onboarding:
                OnboardingShellView(coordinator: coordinator)
            case .connected:
                ConnectedShellView(coordinator: coordinator)
            }
        }
        .animation(.snappy(duration: 0.28), value: coordinator.route)
    }
}
