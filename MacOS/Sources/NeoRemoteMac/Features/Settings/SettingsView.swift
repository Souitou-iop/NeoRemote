import SwiftUI

struct SettingsView: View {
    @ObservedObject var service: DesktopRemoteService
    @State private var isEventsExpanded = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                generalCard
                permissionCard
                eventsCard
            }
            .padding(24)
        }
        .frame(minWidth: 620, idealWidth: 780, maxWidth: 1600, minHeight: 460, idealHeight: 620, maxHeight: 1400)
        .background(Color(nsColor: .windowBackgroundColor))
    }

    private var generalCard: some View {
        SettingsCard(title: "常规", subtitle: "应用入口与监听反馈") {
            VStack(alignment: .leading, spacing: 16) {
                Toggle(isOn: Binding(
                    get: { service.showsMenuBarExtra },
                    set: { service.setMenuBarVisibility($0) }
                )) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("在菜单栏显示")
                        Text(service.showsMenuBarExtra ? "显示菜单栏快捷入口，便于快速查看状态和断开连接" : "隐藏菜单栏入口，仅保留 Dock 图标和主窗口入口")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .toggleStyle(.switch)

                Toggle(isOn: Binding(
                    get: { service.isListeningSoundEnabled },
                    set: { service.setListeningSoundEnabled($0) }
                )) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("监听音效")
                        Text(service.isListeningSoundEnabled ? "开启监听与关闭监听时播放系统音效" : "关闭监听状态变化的系统音效")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .toggleStyle(.switch)
            }
        }
    }

    private var permissionCard: some View {
        SettingsCard(title: "辅助功能权限", subtitle: "没有权限时仍可连接，但不会实际注入鼠标事件") {
            VStack(alignment: .leading, spacing: 14) {
                LabeledContent("权限状态") {
                    Text(service.permissionStatus == .granted ? "已授权" : "未授权")
                        .foregroundStyle(service.permissionStatus == .granted ? .green : .orange)
                }

                HStack(spacing: 12) {
                    Button("请求授权") {
                        service.requestAccessibilityPermission()
                    }
                    .buttonStyle(.borderedProminent)

                    Button("打开系统设置") {
                        service.openAccessibilitySettings()
                    }
                    .buttonStyle(.bordered)

                    Button("重新检测") {
                        service.retryPermissionCheck()
                    }
                    .buttonStyle(.bordered)
                }
            }
        }
    }

    private var eventsCard: some View {
        SettingsCard(title: "最近事件", subtitle: "默认折叠，按需展开查看完整记录") {
            VStack(alignment: .leading, spacing: 12) {
                Button {
                    withAnimation(.snappy(duration: 0.28, extraBounce: 0.04)) {
                        isEventsExpanded.toggle()
                    }
                } label: {
                    HStack(spacing: 10) {
                        Text(isEventsExpanded ? "收起最近事件" : "展开最近事件")
                            .font(.callout.weight(.semibold))
                        Spacer()
                        Text("\(service.recentEvents.count)")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                        Image(systemName: "chevron.down")
                            .font(.caption.bold())
                            .rotationEffect(.degrees(isEventsExpanded ? 180 : 0))
                            .foregroundStyle(.secondary)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                if isEventsExpanded {
                    if service.recentEvents.isEmpty {
                        Text("还没有事件记录。")
                            .foregroundStyle(.secondary)
                            .transition(.opacity)
                    } else {
                        ForEach(Array(service.recentEvents.enumerated()), id: \.element.id) { index, event in
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Text(event.title)
                                        .font(.body.weight(.semibold))
                                    Spacer()
                                    Text(event.timestamp, style: .time)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Text(event.detail)
                                    .font(.callout)
                                    .foregroundStyle(.secondary)
                            }
                            .padding(.vertical, 6)
                            .transition(.move(edge: .top).combined(with: .opacity))

                            if index != service.recentEvents.count - 1 {
                                Divider()
                            }
                        }
                    }
                }
            }
            .animation(.snappy(duration: 0.28, extraBounce: 0.04), value: isEventsExpanded)
        }
    }
}

private struct SettingsCard<Content: View>: View {
    let title: String
    let subtitle: String
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.headline)
                Text(subtitle)
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            content
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
    }
}
