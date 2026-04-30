import SwiftUI

struct DevicesView: View {
    @ObservedObject var coordinator: SessionCoordinator

    var body: some View {
        NavigationStack {
            List {
                if let activeEndpoint = coordinator.activeEndpoint {
                    Section("控制状态") {
                        DeviceRow(endpoint: activeEndpoint, detail: "实时控制中")
                        LabeledContent("状态") {
                            Text(coordinator.status.rawValue)
                        }
                        LabeledContent("控制模式") {
                            Text(coordinator.controlMode.displayName)
                        }
                        LabeledContent("地址") {
                            Text("\(activeEndpoint.host):\(activeEndpoint.port)")
                                .monospacedDigit()
                        }
                        Button("断开当前连接") {
                            coordinator.disconnect()
                        }
                    }
                }

                Section("最近连接") {
                    if coordinator.recentDevices.isEmpty {
                        Text("还没有已保存的设备。")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(coordinator.recentDevices) { endpoint in
                            Button {
                                coordinator.connect(to: endpoint)
                            } label: {
                                DeviceRow(endpoint: endpoint, detail: "点击重新连接")
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }

                Section("自动发现") {
                    if coordinator.discoveredDevices.isEmpty {
                        Text("当前未发现新的设备。")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(coordinator.discoveredDevices) { endpoint in
                            Button {
                                coordinator.connect(to: endpoint)
                            } label: {
                                DeviceRow(endpoint: endpoint, detail: "来自局域网扫描")
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            .navigationTitle("Devices")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("刷新") {
                        coordinator.refreshDiscovery()
                    }
                }
            }
        }
    }
}

private struct DeviceRow: View {
    let endpoint: DesktopEndpoint
    let detail: String

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: endpoint.platform.deviceSymbolName)
                .font(.title3)
                .frame(width: 30)
                .foregroundStyle(Color.accentColor)

            VStack(alignment: .leading, spacing: 4) {
                Text(endpoint.displayName)
                    .font(.body.weight(.semibold))
                Text("\(endpoint.host):\(endpoint.port)")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 6)
    }
}

private extension Optional where Wrapped == DesktopPlatform {
    var deviceSymbolName: String {
        switch self {
        case .some(.android):
            return "iphone"
        case .some(.windows):
            return "desktopcomputer"
        case .some(.macOS), .none:
            return "laptopcomputer"
        }
    }
}
