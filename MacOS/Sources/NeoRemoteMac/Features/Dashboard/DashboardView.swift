import SwiftUI

struct DashboardView: View {
    @ObservedObject var service: DesktopRemoteService
    var openSettings: () -> Void = {}
    @State private var selectedClientIDs: Set<UUID> = []

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                headerSection
                if case .firstLaunch = service.dashboardState {
                    firstLaunchCard
                }
                serviceCard
                wiredConnectionCard
                pendingRequestsCard
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

            if case .missingAccessibilityPermission = service.dashboardState {
                Button {
                    openSettings()
                } label: {
                    statusLabel
                }
                .buttonStyle(.plain)
                .help("打开软件设置页，请求辅助功能授权")
            } else {
                statusLabel
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var statusLabel: some View {
        Label(stateTitle, systemImage: stateIcon)
            .font(.callout.weight(.semibold))
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(stateColor.opacity(0.16))
            .foregroundStyle(stateColor)
            .clipShape(Capsule())
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

                if service.permissionStatus == .denied {
                    HStack(spacing: 12) {
                        Label("未授予辅助功能权限，当前连接后不能实际控制鼠标。", systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.orange)
                        Spacer()
                        Button("前往授权") {
                            openSettings()
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    .padding(12)
                    .background(Color.orange.opacity(0.12))
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
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
                    .disabled(service.connectedClients.isEmpty)
                }
            }
        }
    }

    private var sessionCard: some View {
        DashboardCard(title: "已连接设备", subtitle: "多台设备可同时保持连接，输入按服务端收到顺序串行执行") {
            VStack(alignment: .leading, spacing: 14) {
                if service.connectedClients.isEmpty {
                    Text("当前暂无已允许的控制设备。")
                        .foregroundStyle(.secondary)
                } else {
                    HStack(spacing: 12) {
                        Button("断开选中") {
                            service.disconnectClients(clientIDs: selectedClientIDs)
                            selectedClientIDs.removeAll()
                        }
                        .buttonStyle(.bordered)
                        .disabled(selectedClientIDs.isEmpty)

                        Button("断开全部") {
                            service.disconnectClients(clientIDs: Set(service.connectedClients.map(\.id)))
                            selectedClientIDs.removeAll()
                        }
                        .buttonStyle(.bordered)
                    }

                    ForEach(service.connectedClients) { client in
                        HStack(spacing: 12) {
                            Toggle("", isOn: Binding(
                                get: { selectedClientIDs.contains(client.id) },
                                set: { isSelected in
                                    if isSelected {
                                        selectedClientIDs.insert(client.id)
                                    } else {
                                        selectedClientIDs.remove(client.id)
                                    }
                                }
                            ))
                            .labelsHidden()

                            VStack(alignment: .leading, spacing: 4) {
                                HStack(spacing: 8) {
                                    Text(client.displayName)
                                        .font(.body.weight(.semibold))
                                    if let platform = client.platform {
                                        Text(platform)
                                            .font(.caption.weight(.semibold))
                                            .padding(.horizontal, 8)
                                            .padding(.vertical, 3)
                                            .background(Color.blue.opacity(0.12))
                                            .foregroundStyle(.blue)
                                            .clipShape(Capsule())
                                    }
                                    if client.isTrusted {
                                        Text("已信任")
                                            .font(.caption.weight(.semibold))
                                            .padding(.horizontal, 8)
                                            .padding(.vertical, 3)
                                            .background(Color.green.opacity(0.12))
                                            .foregroundStyle(.green)
                                            .clipShape(Capsule())
                                    }
                                }
                                Text("\(client.endpoint.addressSummary) · \(client.connectedAt.formatted(date: .omitted, time: .shortened)) 连接")
                                    .font(.callout.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }

                            Spacer()

                            Button("断开") {
                                selectedClientIDs.remove(client.id)
                                service.disconnectClient(clientID: client.id)
                            }
                            .buttonStyle(.bordered)
                        }
                        .padding(12)
                        .background(Color(nsColor: .controlBackgroundColor))
                        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    }
                }
            }
        }
    }

    private var pendingRequestsCard: some View {
        DashboardCard(title: "待处理请求", subtitle: "新设备连接前需要在 Mac 端允许") {
            VStack(alignment: .leading, spacing: 14) {
                if service.pendingConnectionRequests.isEmpty {
                    Text("当前没有待处理连接请求。")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(service.pendingConnectionRequests) { request in
                        HStack(spacing: 12) {
                            Image(systemName: "iphone.gen3.radiowaves.left.and.right")
                                .foregroundStyle(Color.accentColor)
                                .frame(width: 24)

                            VStack(alignment: .leading, spacing: 4) {
                                Text(request.displayName ?? "未知移动端")
                                    .font(.body.weight(.semibold))
                                Text("\(request.endpoint.addressSummary) · \(request.platform ?? "unknown")")
                                    .font(.callout.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }

                            Spacer()

                            Button("拒绝") {
                                service.rejectConnection(requestID: request.id)
                            }
                            .buttonStyle(.bordered)

                            Button("允许") {
                                service.approveConnection(requestID: request.id)
                            }
                            .buttonStyle(.borderedProminent)
                        }
                        .padding(12)
                        .background(Color(nsColor: .controlBackgroundColor))
                        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    }
                }
            }
        }
    }

    private var wiredConnectionCard: some View {
        DashboardCard(title: "iPhone 有线连接", subtitle: "用数据线连接 iPhone，并打开个人热点后使用下方地址") {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Text("推荐在 iOS 端选择“有线连接 Mac”，输入这里显示的地址。")
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button("刷新地址") {
                        service.refreshConnectionAddresses()
                    }
                    .buttonStyle(.bordered)
                }

                if service.connectionAddresses.isEmpty {
                    Text("暂未发现可用 IPv4 地址。请确认 Mac 网络已连接，或 iPhone USB 个人热点已开启。")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(service.connectionAddresses) { address in
                        HStack(spacing: 12) {
                            Image(systemName: address.isRecommendedForWired ? "cable.connector" : "network")
                                .foregroundStyle(address.isRecommendedForWired ? .green : .secondary)
                                .frame(width: 22)

                            VStack(alignment: .leading, spacing: 3) {
                                HStack(spacing: 8) {
                                    Text(address.label)
                                        .font(.body.weight(.semibold))
                                    if address.isRecommendedForWired {
                                        Text("推荐")
                                            .font(.caption.weight(.semibold))
                                            .padding(.horizontal, 8)
                                            .padding(.vertical, 3)
                                            .background(Color.green.opacity(0.14))
                                            .foregroundStyle(.green)
                                            .clipShape(Capsule())
                                    }
                                }
                                Text(address.addressText)
                                    .font(.callout.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }

                            Spacer()
                        }
                        .padding(12)
                        .background(Color(nsColor: .controlBackgroundColor))
                        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    }
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
            return "已有设备连接"
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
