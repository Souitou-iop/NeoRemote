import Darwin
import Foundation

struct DesktopConnectionAddress: Identifiable, Equatable {
    var id: String { "\(host):\(port)" }
    let host: String
    let port: UInt16
    let label: String
    let isRecommendedForWired: Bool

    var addressText: String { "\(host):\(port)" }
}

protocol DesktopConnectionAddressProviding {
    func loadConnectionAddresses(port: UInt16) -> [DesktopConnectionAddress]
}

struct SystemDesktopConnectionAddressProvider: DesktopConnectionAddressProviding {
    struct InterfaceAddress {
        let name: String
        let host: String
        let isUp: Bool
        let isLoopback: Bool
    }

    func loadConnectionAddresses(port: UInt16) -> [DesktopConnectionAddress] {
        Self.makeAddresses(from: loadInterfaceAddresses(), port: port)
    }

    static func makeAddresses(from interfaces: [InterfaceAddress], port: UInt16) -> [DesktopConnectionAddress] {
        let addresses = interfaces
            .filter { $0.isUp && !$0.isLoopback && !$0.host.isEmpty }
            .map { interface in
                DesktopConnectionAddress(
                    host: interface.host,
                    port: port,
                    label: label(for: interface),
                    isRecommendedForWired: isLikelyIPhoneWiredAddress(interface.host)
                )
            }

        return addresses
            .reduce(into: [DesktopConnectionAddress]()) { result, address in
                if !result.contains(where: { $0.host == address.host && $0.port == address.port }) {
                    result.append(address)
                }
            }
            .sorted { lhs, rhs in
                if lhs.isRecommendedForWired != rhs.isRecommendedForWired {
                    return lhs.isRecommendedForWired && !rhs.isRecommendedForWired
                }
                return lhs.host.localizedStandardCompare(rhs.host) == .orderedAscending
            }
    }

    private func loadInterfaceAddresses() -> [InterfaceAddress] {
        var interfaces: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&interfaces) == 0, let first = interfaces else { return [] }
        defer { freeifaddrs(interfaces) }

        var results: [InterfaceAddress] = []
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let current = cursor {
            let interface = current.pointee
            defer { cursor = interface.ifa_next }

            guard let address = interface.ifa_addr, address.pointee.sa_family == sa_family_t(AF_INET) else {
                continue
            }

            var ipv4 = address.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr }
            var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            guard inet_ntop(AF_INET, &ipv4, &buffer, socklen_t(INET_ADDRSTRLEN)) != nil else {
                continue
            }

            let flags = Int32(interface.ifa_flags)
            let host = buffer.withUnsafeBufferPointer { pointer in
                String(cString: pointer.baseAddress!)
            }

            results.append(
                InterfaceAddress(
                    name: String(cString: interface.ifa_name),
                    host: host,
                    isUp: (flags & IFF_UP) != 0,
                    isLoopback: (flags & IFF_LOOPBACK) != 0
                )
            )
        }
        return results
    }

    private static func label(for interface: InterfaceAddress) -> String {
        if isLikelyIPhoneWiredAddress(interface.host) {
            return "iPhone USB / 个人热点"
        }
        return interface.name
    }

    private static func isLikelyIPhoneWiredAddress(_ host: String) -> Bool {
        host.hasPrefix("172.20.10.")
    }
}
