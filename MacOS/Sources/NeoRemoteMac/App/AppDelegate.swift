import AppKit
import Combine

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private var statusItem: NSStatusItem?
    private var service: DesktopRemoteService?
    private var openDashboard: (() -> Void)?
    private var openSettings: (() -> Void)?
    private var cancellables: Set<AnyCancellable> = []
    private var isConfigured = false

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
    }

    func configure(
        service: DesktopRemoteService,
        openDashboard: @escaping () -> Void,
        openSettings: @escaping () -> Void
    ) {
        self.service = service
        self.openDashboard = openDashboard
        self.openSettings = openSettings

        guard !isConfigured else {
            updateStatusItemVisibility(service.showsMenuBarExtra)
            refreshStatusItem()
            return
        }

        isConfigured = true

        service.$showsMenuBarExtra
            .removeDuplicates()
            .sink { [weak self] visible in
                self?.updateStatusItemVisibility(visible)
            }
            .store(in: &cancellables)

        service.objectWillChange
            .sink { [weak self] _ in
                DispatchQueue.main.async {
                    self?.refreshStatusItem()
                }
            }
            .store(in: &cancellables)

        updateStatusItemVisibility(service.showsMenuBarExtra)
        refreshStatusItem()
    }

    private func updateStatusItemVisibility(_ visible: Bool) {
        if visible {
            if statusItem == nil {
                let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
                item.button?.imagePosition = .imageOnly
                statusItem = item
            }
            refreshStatusItem()
            return
        }

        if let statusItem {
            NSStatusBar.system.removeStatusItem(statusItem)
            self.statusItem = nil
        }
    }

    private func refreshStatusItem() {
        guard let service, let statusItem else { return }

        if let button = statusItem.button {
            button.title = ""
            button.image = makeStatusBarImage(symbolName: service.menuBarSymbolName)
            button.imageScaling = .scaleProportionallyUpOrDown
            button.toolTip = "NeoRemote"
        }

        statusItem.menu = buildMenu(for: service)
    }

    private func makeStatusBarImage(symbolName: String) -> NSImage {
        let configuration = NSImage.SymbolConfiguration(pointSize: 15, weight: .bold, scale: .large)
        let image = NSImage(
            systemSymbolName: symbolName,
            accessibilityDescription: "NeoRemote"
        ) ?? fallbackStatusBarImage()
        let configured = image.withSymbolConfiguration(configuration) ?? image
        configured.isTemplate = true
        configured.size = NSSize(width: 18, height: 18)
        return configured
    }

    private func fallbackStatusBarImage() -> NSImage {
        let image = NSImage(size: NSSize(width: 18, height: 18))
        image.lockFocus()
        let rect = NSRect(x: 3, y: 3, width: 12, height: 12)
        NSColor.labelColor.setFill()
        NSBezierPath(roundedRect: rect, xRadius: 3, yRadius: 3).fill()
        image.unlockFocus()
        image.isTemplate = true
        return image
    }

    private func buildMenu(for service: DesktopRemoteService) -> NSMenu {
        let menu = NSMenu()

        let summaryItem = NSMenuItem(title: service.summaryText, action: nil, keyEquivalent: "")
        summaryItem.isEnabled = false
        menu.addItem(summaryItem)
        menu.addItem(.separator())

        let listeningItem = NSMenuItem(
            title: service.isListeningEnabled ? "关闭监听" : "开启监听",
            action: #selector(toggleListening),
            keyEquivalent: ""
        )
        listeningItem.target = self
        menu.addItem(listeningItem)

        let dashboardItem = NSMenuItem(title: "打开主窗口", action: #selector(openDashboardWindow), keyEquivalent: "")
        dashboardItem.target = self
        menu.addItem(dashboardItem)

        let settingsItem = NSMenuItem(title: "打开设置", action: #selector(openSettingsWindow), keyEquivalent: "")
        settingsItem.target = self
        menu.addItem(settingsItem)

        let disconnectItem = NSMenuItem(title: "断开当前会话", action: #selector(disconnectCurrentSession), keyEquivalent: "")
        disconnectItem.target = self
        disconnectItem.isEnabled = service.activeClient != nil
        menu.addItem(disconnectItem)

        menu.addItem(.separator())

        let quitItem = NSMenuItem(title: "退出 NeoRemote", action: #selector(terminateApp), keyEquivalent: "q")
        quitItem.target = self
        menu.addItem(quitItem)

        return menu
    }

    @objc
    private func toggleListening() {
        guard let service else { return }
        service.setListeningEnabled(!service.isListeningEnabled)
    }

    @objc
    private func openDashboardWindow() {
        openDashboard?()
        NSApp.activate(ignoringOtherApps: true)
    }

    @objc
    private func openSettingsWindow() {
        openSettings?()
        NSApp.activate(ignoringOtherApps: true)
    }

    @objc
    private func disconnectCurrentSession() {
        service?.disconnectCurrentSession()
    }

    @objc
    private func terminateApp() {
        NSApp.terminate(nil)
    }
}
