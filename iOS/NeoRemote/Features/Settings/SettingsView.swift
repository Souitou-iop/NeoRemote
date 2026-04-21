import SwiftUI

struct SettingsView: View {
    @ObservedObject var coordinator: SessionCoordinator

    var body: some View {
        NavigationStack {
            List {
                Section("连接策略") {
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

                Section("说明") {
                    Text("Remote 页坚持纯手势控制，不放显式左右键按钮。后续接入 macOS / Windows Helper 时，只需要扩展 Desktop 平台适配层。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Settings")
        }
    }
}
