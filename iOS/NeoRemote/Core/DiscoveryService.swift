import Foundation

protocol DiscoveryServing: AnyObject {
    var onUpdate: (([DesktopEndpoint]) -> Void)? { get set }
    func start()
    func stop()
    func refresh()
}

final class BonjourDiscoveryService: NSObject, DiscoveryServing {
    var onUpdate: (([DesktopEndpoint]) -> Void)?

    private let browser = NetServiceBrowser()
    private var services: [NetService] = []
    private var discovered: [String: DesktopEndpoint] = [:]

    override init() {
        super.init()
        browser.delegate = self
    }

    func start() {
        refresh()
    }

    func stop() {
        browser.stop()
        services.forEach { service in
            service.stop()
            service.delegate = nil
        }
        services.removeAll()
        discovered.removeAll()
        publish()
    }

    func refresh() {
        stop()
        browser.searchForServices(ofType: "_neoremote._tcp.", inDomain: "local.")
    }

    private func publish() {
        let endpoints = discovered.values
            .sorted { ($0.lastSeenAt ?? .distantPast) > ($1.lastSeenAt ?? .distantPast) }
        onUpdate?(endpoints)
    }

    private func makeEndpoint(from service: NetService) -> DesktopEndpoint? {
        guard
            let host = service.hostName?.trimmingCharacters(in: CharacterSet(charactersIn: ".")),
            service.port > 0,
            let port = UInt16(exactly: service.port)
        else {
            return nil
        }

        let platform = service.name.lowercased().contains("win") ? DesktopPlatform.windows : DesktopPlatform.macOS

        return DesktopEndpoint(
            id: "\(service.name)-\(host)-\(port)",
            displayName: service.name.isEmpty ? "Desktop" : service.name,
            host: host,
            port: port,
            platform: platform,
            lastSeenAt: Date(),
            source: .discovered
        )
    }
}

extension BonjourDiscoveryService: NetServiceBrowserDelegate {
    func netServiceBrowser(
        _ browser: NetServiceBrowser,
        didFind service: NetService,
        moreComing _: Bool
    ) {
        service.delegate = self
        services.append(service)
        service.resolve(withTimeout: 5)
    }

    func netServiceBrowser(
        _ browser: NetServiceBrowser,
        didRemove service: NetService,
        moreComing _: Bool
    ) {
        services.removeAll { $0 === service }
        discovered = discovered.filter { !$0.key.hasPrefix(service.name) }
        publish()
    }
}

extension BonjourDiscoveryService: NetServiceDelegate {
    func netServiceDidResolveAddress(_ sender: NetService) {
        guard let endpoint = makeEndpoint(from: sender) else { return }
        discovered[endpoint.id] = endpoint
        publish()
    }

    func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
        if let code = errorDict[NetService.errorCode] {
            print("NeoRemote discovery resolve failed for \(sender.name): \(code)")
        }
    }
}

final class MockDiscoveryService: DiscoveryServing {
    var onUpdate: (([DesktopEndpoint]) -> Void)?
    var cannedResults: [DesktopEndpoint]

    init(cannedResults: [DesktopEndpoint] = []) {
        self.cannedResults = cannedResults
    }

    func start() {
        onUpdate?(cannedResults)
    }

    func stop() {}

    func refresh() {
        onUpdate?(cannedResults)
    }
}
