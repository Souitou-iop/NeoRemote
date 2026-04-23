import Darwin
import Foundation

enum UDPDiscoveryResponderError: Error, LocalizedError {
    case socketCreationFailed
    case bindFailed(String)

    var errorDescription: String? {
        switch self {
        case .socketCreationFailed:
            return "UDP 发现 socket 创建失败"
        case let .bindFailed(message):
            return "UDP 发现端口绑定失败：\(message)"
        }
    }
}

final class UDPDiscoveryResponder: @unchecked Sendable {
    private let queue: DispatchQueue
    private let discoveryPort: UInt16
    private let displayName: String
    private let requestText = "NEOREMOTE_DISCOVER_V1"

    private var socketFD: Int32 = -1
    private var readSource: DispatchSourceRead?
    private(set) var boundDiscoveryPort: UInt16?
    private var advertisedServicePort: UInt16 = 50505

    init(
        discoveryPort: UInt16 = 51101,
        displayName: String = Host.current().localizedName ?? "NeoRemote Mac",
        queue: DispatchQueue = DispatchQueue(label: "com.neoremote.mac.discovery.udp")
    ) {
        self.discoveryPort = discoveryPort
        self.displayName = displayName
        self.queue = queue
    }

    func start(servicePort: UInt16) throws {
        stop()
        advertisedServicePort = servicePort

        let socket = Darwin.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard socket >= 0 else {
            throw UDPDiscoveryResponderError.socketCreationFailed
        }

        var reuse: Int32 = 1
        setsockopt(socket, SOL_SOCKET, SO_REUSEADDR, &reuse, socklen_t(MemoryLayout<Int32>.size))

        let flags = fcntl(socket, F_GETFL, 0)
        if flags >= 0 {
            _ = fcntl(socket, F_SETFL, flags | O_NONBLOCK)
        }

        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = in_port_t(discoveryPort.bigEndian)
        address.sin_addr = in_addr(s_addr: INADDR_ANY)

        let bindResult = withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockaddrPointer in
                Darwin.bind(socket, sockaddrPointer, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }

        guard bindResult == 0 else {
            let message = String(cString: strerror(errno))
            Darwin.close(socket)
            throw UDPDiscoveryResponderError.bindFailed(message)
        }

        socketFD = socket
        boundDiscoveryPort = resolveBoundPort(socket)

        let source = DispatchSource.makeReadSource(fileDescriptor: socket, queue: queue)
        source.setEventHandler { [weak self] in
            self?.receiveAvailablePackets(socket: socket)
        }
        source.setCancelHandler {
            Darwin.close(socket)
        }
        readSource = source
        source.resume()
    }

    func stop() {
        boundDiscoveryPort = nil
        socketFD = -1
        readSource?.cancel()
        readSource = nil
    }

    private func receiveAvailablePackets(socket: Int32) {
        while true {
            var buffer = [UInt8](repeating: 0, count: 512)
            var remoteAddress = sockaddr_storage()
            var remoteLength = socklen_t(MemoryLayout<sockaddr_storage>.size)

            let bytesRead = withUnsafeMutablePointer(to: &remoteAddress) { pointer in
                pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockaddrPointer in
                    recvfrom(
                        socket,
                        &buffer,
                        buffer.count,
                        0,
                        sockaddrPointer,
                        &remoteLength
                    )
                }
            }

            if bytesRead <= 0 {
                return
            }

            let request = String(decoding: buffer.prefix(bytesRead), as: UTF8.self)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            guard request == requestText else {
                continue
            }

            sendResponse(to: remoteAddress, remoteLength: remoteLength, socket: socket)
        }
    }

    private func sendResponse(to remoteAddress: sockaddr_storage, remoteLength: socklen_t, socket: Int32) {
        let response = """
        NEOREMOTE_DESKTOP_V1
        name=\(displayName)
        platform=macos
        port=\(advertisedServicePort)
        """
        let payload = Array(response.utf8)

        var address = remoteAddress
        withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockaddrPointer in
                payload.withUnsafeBytes { bytes in
                    _ = sendto(
                        socket,
                        bytes.baseAddress,
                        bytes.count,
                        0,
                        sockaddrPointer,
                        remoteLength
                    )
                }
            }
        }
    }

    private func resolveBoundPort(_ socket: Int32) -> UInt16? {
        var address = sockaddr_in()
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let result = withUnsafeMutablePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockaddrPointer in
                getsockname(socket, sockaddrPointer, &length)
            }
        }
        guard result == 0 else { return nil }
        return UInt16(bigEndian: address.sin_port)
    }
}
