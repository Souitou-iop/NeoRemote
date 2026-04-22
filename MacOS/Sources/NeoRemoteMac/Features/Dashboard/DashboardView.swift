import SwiftUI

struct DashboardView: View {
    @ObservedObject var service: DesktopRemoteService

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                headerSection
                if case .firstLaunch = service.dashboardState {
                    firstLaunchCard
                }
                serviceCard
                sessionCard
            }
            .padding(24)
        }
        .frame(minWidth: 620, idealWidth: 860, maxWidth: 1800, minHeight: 460, idealHeight: 640, maxHeight: 1400)
        .background(Color(nsColor: .windowBackgroundColor))
        .navigationTitle("NeoRemote")
    }

    private var headerSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("NeoRemote for MacOS")
                .font(.system(size: 28, weight: .bold))
            Text(service.summaryText)
                .font(.title3)
                .foregroundStyle(.secondary)

            Label(stateTitle, systemImage: stateIcon)
                .font(.callout.weight(.semibold))
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(stateColor.opacity(0.16))
                .foregroundStyle(stateColor)
                .clipShape(Capsule())
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var firstLaunchCard: some View {
        DashboardCard(title: "首次引导", subtitle: "先确认首版 helper 工作流，再进入自动监听") {
            VStack(alignment: .leading, spacing: 14) {
                Text("完成首次引导后，NeoRemote 会在后续每次启动时自动发布 Bonjour 并监听 TCP 50505。")
                    .foregroundStyle(.secondary)

                Button("完成引导并开始监听") {
                    service.completeFirstLaunch()
                }
                .buttonStyle(.borderedProminent)
            }
        }
    }

    private var serviceCard: some View {
        DashboardCard(title: "服务状态", subtitle: "Bonjour 发布、监听端口和当前运行状态") {
            VStack(alignment: .leading, spacing: 14) {
                LabeledContent("当前摘要") {
                    Text(service.summaryText)
                }
                LabeledContent("监听") {
                    Text(service.isListeningEnabled ? (service.isListening ? "已启动" : "准备启动") : "已关闭")
                }

                HStack(spacing: 12) {
                    Button(service.isListeningEnabled ? "关闭监听" : "开启监听") {
                        service.setListeningEnabled(!service.isListeningEnabled)
                    }
                    .buttonStyle(.borderedProminent)

                    Button("断开当前会话") {
                        service.disconnectCurrentSession()
                    }
                    .buttonStyle(.bordered)
                    .disabled(service.activeClient == nil)
                }
            }
        }
    }

    private var sessionCard: some View {
        DashboardCard(title: "当前会话", subtitle: "最多仅允许 1 台 iPhone 接管这台 Mac") {
            VStack(alignment: .leading, spacing: 14) {
                if let activeClient = service.activeClient {
                    LabeledContent("设备") {
                        Text(activeClient.displayName)
                    }
                    LabeledContent("地址") {
                        Text(activeClient.addressSummary)
                            .monospacedDigit()
                    }
                } else {
                    Text("当前暂无活跃控制会话。")
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private var stateTitle: String {
        switch service.dashboardState {
        case .firstLaunch:
            return "等待完成首次引导"
        case .listeningDisabled:
            return "监听功能已关闭"
        case .missingAccessibilityPermission:
            return "缺少辅助功能权限"
        case .idleListening:
            return "正在等待设备连接"
        case .connected:
            return "已有设备正在控制"
        case .occupied:
            return "已拒绝新的占用请求"
        case .error:
            return "服务异常"
        }
    }

    private var stateIcon: String {
        switch service.dashboardState {
        case .firstLaunch:
            return "flag"
        case .listeningDisabled:
            return "dot.radiowaves.left.and.right.slash"
        case .missingAccessibilityPermission:
            return "exclamationmark.triangle"
        case .idleListening:
            return "dot.radiowaves.left.and.right"
        case .connected:
            return "cursorarrow.rays"
        case .occupied:
            return "person.crop.circle.badge.exclamationmark"
        case .error:
            return "bolt.horizontal.circle"
        }
    }

    private var stateColor: Color {
        switch service.dashboardState {
        case .firstLaunch:
            return .blue
        case .listeningDisabled:
            return .secondary
        case .missingAccessibilityPermission:
            return .orange
        case .idleListening:
            return .green
        case .connected:
            return .accentColor
        case .occupied:
            return .purple
        case .error:
            return .red
        }
    }
}

private struct DashboardCard<Content: View>: View {
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
