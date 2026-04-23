import CoreGraphics
import XCTest
@testable import NeoRemote

final class TouchSurfaceInputAdapterTests: XCTestCase {
    func testSingleFingerMovementEmitsMoveCommand() {
        var adapter = TouchSurfaceInputAdapter()

        _ = adapter.touchBegan(id: 1, point: CGPoint(x: 0, y: 0), timestamp: 0)
        let output = adapter.touchMoved(id: 1, point: CGPoint(x: 20, y: 10), timestamp: 0.02)

        XCTAssertEqual(output.commands, [.move(dx: 20, dy: 10)])
        XCTAssertEqual(output.semanticEvent, .moving)
    }

    func testTwoFingerGestureEmitsScrollCommand() {
        var adapter = TouchSurfaceInputAdapter()

        _ = adapter.touchBegan(id: 1, point: CGPoint(x: 0, y: 50), timestamp: 0)
        _ = adapter.touchBegan(id: 2, point: CGPoint(x: 30, y: 60), timestamp: 0)
        let output = adapter.touchMoved(id: 1, point: CGPoint(x: 0, y: 30), timestamp: 0.04)

        XCTAssertEqual(output.commands, [.scroll(deltaY: 10)])
        XCTAssertEqual(output.semanticEvent, .scrolling)
    }

    func testQuickTapEmitsPrimaryTap() {
        var adapter = TouchSurfaceInputAdapter()

        _ = adapter.touchBegan(id: 1, point: CGPoint(x: 10, y: 10), timestamp: 0)
        let output = adapter.touchEnded(id: 1, point: CGPoint(x: 10, y: 10), timestamp: 0.08)

        XCTAssertEqual(output.commands, [.tap(kind: .primary)])
        XCTAssertEqual(output.semanticEvent, .tap)
    }

    func testDoubleTapDragEmitsDragLifecycle() {
        var adapter = TouchSurfaceInputAdapter()

        _ = adapter.touchBegan(id: 1, point: CGPoint(x: 10, y: 10), timestamp: 0)
        _ = adapter.touchEnded(id: 1, point: CGPoint(x: 10, y: 10), timestamp: 0.05)
        _ = adapter.touchBegan(id: 2, point: CGPoint(x: 10, y: 10), timestamp: 0.18)

        let start = adapter.touchMoved(id: 2, point: CGPoint(x: 30, y: 20), timestamp: 0.2)
        let end = adapter.touchEnded(id: 2, point: CGPoint(x: 30, y: 20), timestamp: 0.28)

        XCTAssertEqual(start.commands.first, .drag(state: .started, dx: 0, dy: 0))
        XCTAssertEqual(start.semanticEvent, .dragStarted)
        XCTAssertEqual(end.commands, [.drag(state: .ended, dx: 0, dy: 0)])
    }
}
