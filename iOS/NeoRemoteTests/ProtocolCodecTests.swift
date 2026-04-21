import XCTest
@testable import NeoRemote

final class ProtocolCodecTests: XCTestCase {
    func testEncodeMoveCommandUsesJSONEnvelope() throws {
        let codec = ProtocolCodec()

        let data = try codec.encode(.move(dx: 12.5, dy: -3.2))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let dx = try XCTUnwrap(json["dx"] as? Double)
        let dy = try XCTUnwrap(json["dy"] as? Double)

        XCTAssertEqual(json["type"] as? String, "move")
        XCTAssertEqual(dx, 12.5, accuracy: 0.001)
        XCTAssertEqual(dy, -3.2, accuracy: 0.001)
    }

    func testDecodeKnownStatusMessage() throws {
        let codec = ProtocolCodec()
        let data = """
        {"type":"status","message":"desktop-ready"}
        """.data(using: .utf8)!

        let message = try codec.decode(data)

        XCTAssertEqual(message, .status("desktop-ready"))
    }

    func testDecodeUnknownMessageFallsBackToUnknownCase() throws {
        let codec = ProtocolCodec()
        let data = """
        {"type":"mystery"}
        """.data(using: .utf8)!

        let message = try codec.decode(data)

        XCTAssertEqual(message, .unknown(type: "mystery"))
    }
}
