import Darwin
import XCTest
@testable import NeoRemoteMac

final class UDPDiscoveryResponderTests: XCTestCase {
    func testRespondsToDiscoveryRequestWithMacOSServicePayload() throws {
        let responder = UDPDiscoveryResponder(discoveryPort: 0, displayName: "NeoRemote Test Mac")
        try responder.start(servicePort: 50505)
        defer { responder.stop() }

        let discoveryPort = try XCTUnwrap(responder.boundDiscoveryPort)
        let response = try sendDiscoveryRequest(to: discoveryPort)

        XCTAssertTrue(response.hasPrefix("NEOREMOTE_DESKTOP_V1"))
        XCTAssertTrue(response.contains("name=NeoRemote Test Mac"))
        XCTAssertTrue(response.contains("platform=macos"))
        XCTAssertTrue(response.contains("port=50505"))
    }

    private func sendDiscoveryRequest(to port: UInt16) throws -> String {
        let socket = Darwin.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        XCTAssertGreaterThanOrEqual(socket, 0)
        defer { Darwin.close(socket) }

        var timeout = timeval(tv_sec: 1, tv_usec: 0)
        setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))

        let request = Array("NEOREMOTE_DISCOVER_V1".utf8)
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = in_port_t(port.bigEndian)
        inet_pton(AF_INET, "127.0.0.1", &address.sin_addr)

        let sent = withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockaddrPointer in
                request.withUnsafeBytes { bytes in
                    sendto(
                        socket,
                        bytes.baseAddress,
                        bytes.count,
                        0,
                        sockaddrPointer,
                        socklen_t(MemoryLayout<sockaddr_in>.size)
                    )
                }
            }
        }
        XCTAssertEqual(sent, request.count)

        var buffer = [UInt8](repeating: 0, count: 512)
        let bytesRead = recv(socket, &buffer, buffer.count, 0)
        XCTAssertGreaterThan(bytesRead, 0)
        return String(decoding: buffer.prefix(bytesRead), as: UTF8.self)
    }
}
