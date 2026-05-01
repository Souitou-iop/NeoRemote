import SwiftUI

struct SettingsView: View {
    @ObservedObject var coordinator: SessionCoordinator

    var body: some View {
        NavigationStack {
            List {
                Section("默认控制模式") {
                    Picker(
                        "启动后进入",
                        selection: Binding(
                            get: { coordinator.defaultControlMode },
                            set: { coordinator.setDefaultControlMode($0) }
                        )
                    ) {
                        ForEach(ControlMode.allCases) { mode in
                            Text(mode.displayName).tag(mode)
                        }
                    }
                    .pickerStyle(.segmented)

                    LabeledContent("当前模式") {
                        Text(coordinator.controlMode.displayName)
                    }
                }

                Section("触控反馈") {
                    Toggle(
                        "震动反馈",
                        isOn: Binding(
                            get: { coordinator.isHapticsEnabled },
                            set: { coordinator.setHapticsEnabled($0) }
                        )
                    )
                }

                Section("连接策略") {
                    LabeledContent("自动发现") {
                        Text("Bonjour / LAN")
                    }
                    LabeledContent("协议编码") {
                        Text("JSON v1")
                    }
                }

                Section("当前会话") {
                    LabeledContent("Desktop") {
                        Text(coordinator.activeEndpoint?.displayName ?? "未连接")
                    }
                }

                Section("维护") {
                    Button("断开当前连接") {
                        coordinator.disconnect()
                    }
                }
            }
            .navigationTitle("Settings")
        }
    }
}
