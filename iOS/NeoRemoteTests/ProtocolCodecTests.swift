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

    func testEncodeSecondaryDragIncludesButton() throws {
        let codec = ProtocolCodec()

        let data = try codec.encode(.drag(state: .started, button: .secondary, dx: 4, dy: -2))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])

        XCTAssertEqual(json["type"] as? String, "drag")
        XCTAssertEqual(json["state"] as? String, "started")
        XCTAssertEqual(json["button"] as? String, "secondary")
    }

    func testEncodeMiddleTapIncludesButton() throws {
        let codec = ProtocolCodec()

        let data = try codec.encode(.tap(kind: .middle))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])

        XCTAssertEqual(json["type"] as? String, "tap")
        XCTAssertEqual(json["button"] as? String, "middle")
    }

    func testEncodeScrollIncludesBothAxes() throws {
        let codec = ProtocolCodec()

        let data = try codec.encode(.scroll(deltaX: 7, deltaY: -3))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])

        XCTAssertEqual(json["type"] as? String, "scroll")
        XCTAssertEqual(json["deltaX"] as? Double, 7)
        XCTAssertEqual(json["deltaY"] as? Double, -3)
    }

    func testEncodeVideoActionUsesCamelCaseActionName() throws {
        let codec = ProtocolCodec()

        let data = try codec.encode(.videoAction(.swipeUp))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])

        XCTAssertEqual(json["type"] as? String, "videoAction")
        XCTAssertEqual(json["action"] as? String, "swipeUp")
    }

    func testEncodeFavoriteVideoAction() throws {
        let codec = ProtocolCodec()

        let data = try codec.encode(.videoAction(.favorite))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])

        XCTAssertEqual(json["type"] as? String, "videoAction")
        XCTAssertEqual(json["action"] as? String, "favorite")
    }

    func testEncodeSystemActionUsesCamelCaseEnvelope() throws {
        let codec = ProtocolCodec()

        let data = try codec.encode(.systemAction(.recents))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])

        XCTAssertEqual(json["type"] as? String, "systemAction")
        XCTAssertEqual(json["action"] as? String, "recents")
    }

    func testEncodeScreenGestureIncludesNormalizedCoordinates() throws {
        let codec = ProtocolCodec()

        let data = try codec.encode(
            .screenGesture(
                kind: .swipe,
                startX: 0.25,
                startY: 0.75,
                endX: 0.25,
                endY: 0.2,
                durationMs: 240
            )
        )
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])

        XCTAssertEqual(json["type"] as? String, "screenGesture")
        XCTAssertEqual(json["kind"] as? String, "swipe")
        XCTAssertEqual(json["startX"] as? Double, 0.25)
        XCTAssertEqual(json["startY"] as? Double, 0.75)
        XCTAssertEqual(json["endX"] as? Double, 0.25)
        XCTAssertEqual(json["endY"] as? Double, 0.2)
        XCTAssertEqual(json["durationMs"] as? Int, 240)
    }

    func testEncodeClientHelloIncludesDeviceIdentity() throws {
        let codec = ProtocolCodec()

        let data = try codec.encode(.clientHello(ClientHelloPayload(clientId: "ios-1", displayName: "Ebato 的 iPhone", platform: "ios")))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])

        XCTAssertEqual(json["type"] as? String, "clientHello")
        XCTAssertEqual(json["clientId"] as? String, "ios-1")
        XCTAssertEqual(json["displayName"] as? String, "Ebato 的 iPhone")
        XCTAssertEqual(json["platform"] as? String, "ios")
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

    func testJsonStreamDecoderSplitsBackToBackMessages() throws {
        var decoder = JsonMessageStreamDecoder()

        let payloads = decoder.append(#"{"type":"ack"}{"type":"heartbeat"}"#.data(using: .utf8)!)

        XCTAssertEqual(payloads.count, 2)
        XCTAssertEqual(String(data: try XCTUnwrap(payloads.first), encoding: .utf8), #"{"type":"ack"}"#)
        XCTAssertEqual(String(data: try XCTUnwrap(payloads.last), encoding: .utf8), #"{"type":"heartbeat"}"#)
    }

    func testJsonStreamDecoderWaitsForPartialPayload() {
        var decoder = JsonMessageStreamDecoder()

        let firstChunk = decoder.append(#"{"type":"status","#.data(using: .utf8)!)
        let secondChunk = decoder.append(#""message":"desktop-ready"}"#.data(using: .utf8)!)

        XCTAssertTrue(firstChunk.isEmpty)
        XCTAssertEqual(secondChunk.count, 1)
    }
}
