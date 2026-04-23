import SwiftUI

struct SettingsView: View {
    @ObservedObject var coordinator: SessionCoordinator

    var body: some View {
        NavigationStack {
            List {
                Section("连接策略") {
                    Toggle(
                        "震动反馈",
                        isOn: Binding(
                            get: { coordinator.isHapticsEnabled },
                            set: { coordinator.setHapticsEnabled($0) }
                        )
                    )

                    LabeledContent("自动发现") {
                        Text("Bonjour / LAN")
                    }
                    LabeledContent("协议编码") {
                        Text("JSON v1")
                    }
                    LabeledContent("恢复策略") {
                        Text("启动自动恢复最近桌面端")
                    }
                }

                Section("当前会话") {
                    LabeledContent("状态") {
                        Text(coordinator.status.rawValue)
                    }
                    LabeledContent("Desktop") {
                        Text(coordinator.activeEndpoint?.displayName ?? "未连接")
                    }
                }

                Section("维护") {
                    Button("清空最近设备") {
                        coordinator.clearRecentDevices()
                    }
                    .foregroundStyle(.red)

                    Button("断开当前连接") {
                        coordinator.disconnect()
                    }
                }
            }
            .navigationTitle("Settings")
        }
    }
}
