import SwiftUI

struct OnboardingShellView: View {
    @ObservedObject var coordinator: SessionCoordinator
    @State private var showingManualSheet = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    heroSection
                    connectionStatusSection

                    if !coordinator.discoveredDevices.isEmpty {
                        deviceSection(
                            title: "附近的 Desktop",
                            subtitle: "自动发现到的局域网桌面端",
                            devices: coordinator.discoveredDevices
                        )
                    }

                    if !coordinator.recentDevices.isEmpty {
                        deviceSection(
                            title: "最近连接",
                            subtitle: "下次进入时会优先恢复这些桌面端",
                            devices: coordinator.recentDevices
                        )
                    }

                    manualSection
                }
                .padding(20)
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle("NeoRemote")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("刷新") {
                        coordinator.refreshDiscovery()
                    }
                }
            }
            .sheet(isPresented: $showingManualSheet) {
                ManualConnectSheet(coordinator: coordinator)
                    .presentationDetents([.medium])
            }
        }
    }

    private var heroSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("把iPhone变成你的鼠标/触控板")
                .font(.system(.largeTitle, design: .rounded, weight: .bold))
                .fixedSize(horizontal: false, vertical: true)

            HStack(spacing: 12) {
                Label("自动发现", systemImage: "dot.radiowaves.left.and.right")
                Label("手动兜底", systemImage: "network")
                Label("纯原生", systemImage: "swift")
            }
            .font(.subheadline.weight(.medium))
            .foregroundStyle(.secondary)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            LinearGradient(
                colors: [Color.blue.opacity(0.14), Color.cyan.opacity(0.08)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        )
        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
    }

    private var connectionStatusSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("连接状态")
                .font(.headline)

            HStack(alignment: .center, spacing: 12) {
                Circle()
                    .fill(statusColor)
                    .frame(width: 12, height: 12)

                VStack(alignment: .leading, spacing: 4) {
                    Text(coordinator.status.rawValue.capitalized)
                        .font(.subheadline.weight(.semibold))
                    Text(coordinator.statusMessage)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Spacer()

                if coordinator.status == .connecting || coordinator.status == .discovering || coordinator.status == .reconnecting {
                    ProgressView()
                }
            }

            if let errorMessage = coordinator.errorMessage {
                Text(errorMessage)
                    .font(.footnote)
                    .foregroundStyle(.red)
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private var manualSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("手动连接")
                .font(.headline)

            Text("如果当前网络环境无法自动发现 Desktop，可以直接输入地址和端口。")
                .font(.footnote)
                .foregroundStyle(.secondary)

            Button {
                showingManualSheet = true
            } label: {
                HStack {
                    Label("输入地址连接", systemImage: "keyboard")
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.caption.bold())
                }
                .padding()
                .background(Color(uiColor: .secondarySystemGroupedBackground))
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            }
            .buttonStyle(.plain)
        }
    }

    private func deviceSection(title: String, subtitle: String, devices: [DesktopEndpoint]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(.headline)

            Text(subtitle)
                .font(.footnote)
                .foregroundStyle(.secondary)

            ForEach(devices) { device in
                Button {
                    coordinator.connect(to: device)
                } label: {
                    DeviceCard(endpoint: device, actionTitle: "连接")
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var statusColor: Color {
        switch coordinator.status {
        case .connected:
            return .green
        case .connecting, .discovering, .reconnecting:
            return .orange
        case .failed:
            return .red
        case .disconnected:
            return .gray
        }
    }
}

private struct ManualConnectSheet: View {
    @ObservedObject var coordinator: SessionCoordinator
    @Environment(\.dismiss) private var dismiss
    @State private var host: String = ""
    @State private var port: String = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Desktop 地址") {
                    TextField("192.168.1.11", text: $host)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()

                    TextField("50505", text: $port)
                        .keyboardType(.numberPad)
                }

                Section {
                    Button("连接 Desktop") {
                        coordinator.manualConnectDraft = ManualConnectDraft(host: host, port: port)
                        coordinator.connectUsingManualDraft()
                        dismiss()
                    }
                }
            }
            .navigationTitle("手动连接")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear {
                host = coordinator.manualConnectDraft.host
                port = coordinator.manualConnectDraft.port
            }
        }
    }
}

private struct DeviceCard: View {
    let endpoint: DesktopEndpoint
    let actionTitle: String

    var body: some View {
        HStack(alignment: .center, spacing: 14) {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(Color.blue.opacity(0.12))
                .frame(width: 48, height: 48)
                .overlay {
                    Image(systemName: endpoint.platform == .windows ? "desktopcomputer.trianglebadge.exclamationmark" : "laptopcomputer")
                        .font(.headline)
                        .foregroundStyle(.blue)
                }

            VStack(alignment: .leading, spacing: 4) {
                Text(endpoint.displayName)
                    .font(.body.weight(.semibold))
                Text("\(endpoint.host):\(endpoint.port)")
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Text(actionTitle)
                .font(.footnote.weight(.semibold))
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color.accentColor.opacity(0.12))
                .clipShape(Capsule())
        }
        .padding(16)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}
