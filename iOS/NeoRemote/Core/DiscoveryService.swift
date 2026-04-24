import Foundation
import Darwin

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
    private let udpQueue = DispatchQueue(label: "com.neoremote.discovery.udp", qos: .utility)
    private let udpLock = NSLock()
    private var udpSocket: Int32 = -1
    private var udpSessionID = UUID()

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
        stopUDPFallbackDiscovery()
        discovered.removeAll()
        publish()
    }

    func refresh() {
        stop()
        browser.searchForServices(ofType: "_neoremote._tcp.", inDomain: "local.")
        startUDPFallbackDiscovery()
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

private extension BonjourDiscoveryService {
    func startUDPFallbackDiscovery() {
        let sessionID = UUID()
        udpLock.lock()
        udpSessionID = sessionID
        udpLock.unlock()

        udpQueue.async { [weak self] in
            self?.runUDPFallbackDiscovery(sessionID: sessionID)
        }
    }

    func stopUDPFallbackDiscovery() {
        udpLock.lock()
        udpSessionID = UUID()
        let socket = udpSocket
        udpSocket = -1
        udpLock.unlock()

        if socket >= 0 {
            Darwin.close(socket)
        }
    }

    func runUDPFallbackDiscovery(sessionID: UUID) {
        let socketFD = Darwin.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard socketFD >= 0 else { return }

        var reuse: Int32 = 1
        setsockopt(socketFD, SOL_SOCKET, SO_REUSEADDR, &reuse, socklen_t(MemoryLayout<Int32>.size))
        var broadcast: Int32 = 1
        setsockopt(socketFD, SOL_SOCKET, SO_BROADCAST, &broadcast, socklen_t(MemoryLayout<Int32>.size))
        var timeout = timeval(tv_sec: 0, tv_usec: 250_000)
        setsockopt(socketFD, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))

        udpLock.lock()
        guard udpSessionID == sessionID else {
            udpLock.unlock()
            Darwin.close(socketFD)
            return
        }
        udpSocket = socketFD
        udpLock.unlock()

        defer {
            udpLock.lock()
            if udpSessionID == sessionID, udpSocket == socketFD {
                udpSocket = -1
            }
            udpLock.unlock()
            Darwin.close(socketFD)
        }

        let request = Array(Constants.udpDiscoveryRequest.utf8)
        let targets = udpBroadcastTargets()

        for attempt in 0 ..< Constants.udpDiscoveryAttempts {
            guard isActiveUDPSession(sessionID) else { return }

            targets.forEach { target in
                var address = sockaddr_in()
                address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
                address.sin_family = sa_family_t(AF_INET)
                address.sin_port = in_port_t(Constants.udpDiscoveryPort.bigEndian)
                inet_pton(AF_INET, target, &address.sin_addr)

                withUnsafePointer(to: &address) { pointer in
                    pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockaddrPointer in
                        request.withUnsafeBytes { bytes in
                            _ = sendto(
                                socketFD,
                                bytes.baseAddress,
                                bytes.count,
                                0,
                                sockaddrPointer,
                                socklen_t(MemoryLayout<sockaddr_in>.size)
                            )
                        }
                    }
                }
            }

            let deadline = Date().addingTimeInterval(Constants.udpReceiveWindow)
            while isActiveUDPSession(sessionID), Date() < deadline {
                var buffer = [CChar](repeating: 0, count: 512)
                var remoteAddress = sockaddr_in()
                var remoteLength = socklen_t(MemoryLayout<sockaddr_in>.size)

                let bytesRead = withUnsafeMutablePointer(to: &remoteAddress) { pointer in
                    pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockaddrPointer in
                        recvfrom(
                            socketFD,
                            &buffer,
                            buffer.count - 1,
                            0,
                            sockaddrPointer,
                            &remoteLength
                        )
                    }
                }

                if bytesRead <= 0 {
                    continue
                }

                let payload = String(cString: buffer)
                handleUDPFallbackResponse(payload: payload, remoteAddress: remoteAddress)
            }

            if attempt < Constants.udpDiscoveryAttempts - 1 {
                usleep(useconds_t(Constants.udpRetryDelay * 1_000_000))
            }
        }
    }

    func handleUDPFallbackResponse(payload: String, remoteAddress: sockaddr_in) {
        let trimmedPayload = payload.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmedPayload.hasPrefix(Constants.udpDiscoveryResponsePrefix) else { return }

        let fields = trimmedPayload
            .split(separator: "\n")
            .dropFirst()
            .reduce(into: [String: String]()) { partialResult, line in
                guard let separator = line.firstIndex(of: "=") else { return }
                let key = String(line[..<separator])
                let value = String(line[line.index(after: separator)...])
                partialResult[key] = value
            }

        guard let portText = fields["port"], let port = UInt16(portText) else { return }

        var address = remoteAddress.sin_addr
        var hostBuffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        guard inet_ntop(AF_INET, &address, &hostBuffer, socklen_t(INET_ADDRSTRLEN)) != nil else { return }
        let host = String(cString: hostBuffer)

        let platform: DesktopPlatform? = {
            switch fields["platform"]?.lowercased() {
            case "windows":
                return .windows
            case "macos":
                return .macOS
            default:
                return .windows
            }
        }()

        let name = fields["name"].flatMap { $0.isEmpty ? nil : $0 } ?? "NeoRemote Windows"
        let endpoint = DesktopEndpoint(
            id: "\(name)-\(host)-\(port)-udp",
            displayName: name,
            host: host,
            port: port,
            platform: platform,
            lastSeenAt: Date(),
            source: .discovered
        )

        DispatchQueue.main.async { [weak self] in
            self?.discovered[endpoint.deduplicationKey] = endpoint
            self?.publish()
        }
    }

    func isActiveUDPSession(_ sessionID: UUID) -> Bool {
        udpLock.lock()
        defer { udpLock.unlock() }
        return udpSessionID == sessionID
    }

    func udpBroadcastTargets() -> [String] {
        var addresses = Set(["255.255.255.255"])
        var interfaces: UnsafeMutablePointer<ifaddrs>?

        guard getifaddrs(&interfaces) == 0, let first = interfaces else {
            return Array(addresses)
        }

        defer { freeifaddrs(interfaces) }

        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let current = cursor {
            let interface = current.pointee
            defer { cursor = interface.ifa_next }

            let flags = Int32(interface.ifa_flags)
            guard (flags & IFF_UP) != 0, (flags & IFF_LOOPBACK) == 0, (flags & IFF_BROADCAST) != 0 else { continue }
            guard let broadcastAddress = interface.ifa_dstaddr, broadcastAddress.pointee.sa_family == sa_family_t(AF_INET) else {
                continue
            }

            var address = broadcastAddress.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr }
            var hostBuffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            if inet_ntop(AF_INET, &address, &hostBuffer, socklen_t(INET_ADDRSTRLEN)) != nil {
                addresses.insert(String(cString: hostBuffer))
            }
        }

        return Array(addresses)
    }

    enum Constants {
        static let udpDiscoveryPort: UInt16 = 51101
        static let udpDiscoveryRequest = "NEOREMOTE_DISCOVER_V1"
        static let udpDiscoveryResponsePrefix = "NEOREMOTE_DESKTOP_V1"
        static let udpDiscoveryAttempts = 3
        static let udpReceiveWindow: TimeInterval = 0.8
        static let udpRetryDelay: TimeInterval = 0.3
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
        discovered = discovered.filter { $0.value.displayName != service.name }
        publish()
    }
}

extension BonjourDiscoveryService: NetServiceDelegate {
    func netServiceDidResolveAddress(_ sender: NetService) {
        guard let endpoint = makeEndpoint(from: sender) else { return }
        discovered[endpoint.deduplicationKey] = endpoint
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
