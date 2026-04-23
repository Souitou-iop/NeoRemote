import Foundation
import XCTest
@testable import NeoRemoteMac

final class ProtocolCodecTests: XCTestCase {
    func testDecodeMoveCommand() throws {
        let codec = ProtocolCodec()
        let data = #"{"type":"move","dx":12.5,"dy":-8}"#.data(using: .utf8)!

        XCTAssertEqual(try codec.decodeCommand(data), .move(dx: 12.5, dy: -8))
    }

    func testDecodeTapCommand() throws {
        let codec = ProtocolCodec()
        let data = #"{"type":"tap","button":"primary"}"#.data(using: .utf8)!

        XCTAssertEqual(try codec.decodeCommand(data), .tap(kind: .primary))
    }

    func testDecodeSecondaryDragCommand() throws {
        let codec = ProtocolCodec()
        let data = #"{"type":"drag","state":"started","button":"secondary","dx":4,"dy":-2}"#.data(using: .utf8)!

        XCTAssertEqual(try codec.decodeCommand(data), .drag(state: .started, button: .secondary, dx: 4, dy: -2))
    }

    func testDecodeLegacyDragDefaultsToPrimaryButton() throws {
        let codec = ProtocolCodec()
        let data = #"{"type":"drag","state":"started","dx":4,"dy":-2}"#.data(using: .utf8)!

        XCTAssertEqual(try codec.decodeCommand(data), .drag(state: .started, button: .primary, dx: 4, dy: -2))
    }

    func testEncodeStatusMessage() throws {
        let codec = ProtocolCodec()
        let payload = try codec.encode(.status("ok"))
        let json = String(decoding: payload, as: UTF8.self)

        XCTAssertTrue(json.contains(#""type":"status""#))
        XCTAssertTrue(json.contains(#""message":"ok""#))
    }

    func testStreamDecoderSplitsMultipleJSONObjects() {
        var decoder = JSONMessageStreamDecoder()
        let data = #"{"type":"heartbeat"}{"type":"tap","button":"primary"}"#.data(using: .utf8)!

        let payloads = decoder.append(data)

        XCTAssertEqual(payloads.count, 2)
    }
}
