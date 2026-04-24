import CoreGraphics
import XCTest
@testable import NeoRemote

final class TouchSurfaceInputAdapterTests: XCTestCase {
    func testSingleFingerMovementEmitsMoveCommand() {
        var adapter = TouchSurfaceInputAdapter()

        _ = adapter.touchBegan(id: 1, point: CGPoint(x: 0, y: 0), timestamp: 0)
        let output = adapter.touchMoved(id: 1, point: CGPoint(x: 20, y: 10), timestamp: 0.02)

        XCTAssertEqual(output.commands, [.move(dx: 20, dy: 10)])
        XCTAssertNil(output.semanticEvent)
    }

    func testSingleFingerTapEmitsPrimaryTap() {
        var adapter = TouchSurfaceInputAdapter()

        _ = adapter.touchBegan(id: 1, point: CGPoint(x: 10, y: 10), timestamp: 0)
        let output = adapter.touchEnded(id: 1, point: CGPoint(x: 10, y: 10), timestamp: 0.08)

        XCTAssertEqual(output.commands, [.tap(kind: .primary)])
        XCTAssertEqual(output.semanticEvent, .tap(.primary))
    }

    func testDoubleTapDragEmitsPrimaryDragLifecycle() {
        var adapter = TouchSurfaceInputAdapter()

        _ = adapter.touchBegan(id: 1, point: CGPoint(x: 10, y: 10), timestamp: 0)
        _ = adapter.touchEnded(id: 1, point: CGPoint(x: 10, y: 10), timestamp: 0.05)
        _ = adapter.touchBegan(id: 2, point: CGPoint(x: 10, y: 10), timestamp: 0.18)

        let start = adapter.touchMoved(id: 2, point: CGPoint(x: 30, y: 20), timestamp: 0.2)
        let end = adapter.touchEnded(id: 2, point: CGPoint(x: 30, y: 20), timestamp: 0.28)

        XCTAssertEqual(start.commands.first, .drag(state: .started, button: .primary, dx: 0, dy: 0))
        XCTAssertEqual(start.semanticEvent, .dragStarted(.primary))
        XCTAssertEqual(end.commands, [.drag(state: .ended, button: .primary, dx: 0, dy: 0)])
        XCTAssertEqual(end.semanticEvent, .dragEnded(.primary))
    }

    func testTwoFingerTapEmitsSecondaryTapAfterBothFingersEnd() {
        var adapter = TouchSurfaceInputAdapter()

        _ = adapter.touchBegan(id: 1, point: CGPoint(x: 0, y: 0), timestamp: 0)
        _ = adapter.touchBegan(id: 2, point: CGPoint(x: 20, y: 0), timestamp: 0.02)
        let firstEnd = adapter.touchEnded(id: 1, point: CGPoint(x: 0, y: 0), timestamp: 0.08)
        let secondEnd = adapter.touchEnded(id: 2, point: CGPoint(x: 20, y: 0), timestamp: 0.1)

        XCTAssertEqual(firstEnd, .none)
        XCTAssertEqual(secondEnd.commands, [.tap(kind: .secondary)])
        XCTAssertEqual(secondEnd.semanticEvent, .tap(.secondary))
    }

    func testTwoFingerVerticalMoveEmitsScrollNotSecondaryTap() {
        var adapter = TouchSurfaceInputAdapter()

        _ = adapter.touchBegan(id: 1, point: CGPoint(x: 0, y: 50), timestamp: 0)
        _ = adapter.touchBegan(id: 2, point: CGPoint(x: 30, y: 60), timestamp: 0)
        let output = adapter.touchMoved(id: 1, point: CGPoint(x: 0, y: 20), timestamp: 0.04)

        XCTAssertEqual(output.commands, [.scroll(deltaX: 0, deltaY: 15)])
        XCTAssertEqual(output.semanticEvent, .scrolling)
    }

    func testTwoFingerHorizontalMoveEmitsHorizontalScroll() {
        var adapter = TouchSurfaceInputAdapter()

        _ = adapter.touchBegan(id: 1, point: CGPoint(x: 50, y: 0), timestamp: 0)
        _ = adapter.touchBegan(id: 2, point: CGPoint(x: 60, y: 30), timestamp: 0)
        let output = adapter.touchMoved(id: 1, point: CGPoint(x: 20, y: 0), timestamp: 0.04)

        XCTAssertEqual(output.commands, [.scroll(deltaX: 15, deltaY: 0)])
        XCTAssertEqual(output.semanticEvent, .scrolling)
    }

    func testCursorSensitivityScalesSingleFingerMovement() {
        var adapter = TouchSurfaceInputAdapter(
            settings: TouchSensitivitySettings(cursorSensitivity: 2, swipeSensitivity: 1)
        )

        _ = adapter.touchBegan(id: 1, point: CGPoint(x: 0, y: 0), timestamp: 0)
        let output = adapter.touchMoved(id: 1, point: CGPoint(x: 10, y: 5), timestamp: 0.02)

        XCTAssertEqual(output.commands, [.move(dx: 20, dy: 10)])
    }

    func testSwipeSensitivityLowersScrollActivationThresholdAndScalesOutput() {
        var adapter = TouchSurfaceInputAdapter(
            settings: TouchSensitivitySettings(cursorSensitivity: 1, swipeSensitivity: 2)
        )

        _ = adapter.touchBegan(id: 1, point: CGPoint(x: 0, y: 50), timestamp: 0)
        _ = adapter.touchBegan(id: 2, point: CGPoint(x: 30, y: 60), timestamp: 0)
        let output = adapter.touchMoved(id: 1, point: CGPoint(x: 0, y: 32), timestamp: 0.04)

        XCTAssertEqual(output.commands, [.scroll(deltaX: 0, deltaY: 18)])
    }

    func testTwoFingerHoldAndMoveEmitsSecondaryDragLifecycle() {
        var adapter = TouchSurfaceInputAdapter()

        _ = adapter.touchBegan(id: 1, point: CGPoint(x: 0, y: 0), timestamp: 0)
        _ = adapter.touchBegan(id: 2, point: CGPoint(x: 20, y: 0), timestamp: 0)
        _ = adapter.touchMoved(id: 1, point: CGPoint(x: 16, y: 0), timestamp: 0.24)
        let start = adapter.touchMoved(id: 2, point: CGPoint(x: 36, y: 0), timestamp: 0.25)
        let end = adapter.touchEnded(id: 1, point: CGPoint(x: 16, y: 0), timestamp: 0.3)

        XCTAssertEqual(start.commands.first, .drag(state: .started, button: .secondary, dx: 0, dy: 0))
        XCTAssertEqual(start.semanticEvent, .dragStarted(.secondary))
        XCTAssertEqual(end.commands, [.drag(state: .ended, button: .secondary, dx: 0, dy: 0)])
    }

    func testThreeFingerTapEmitsMiddleTap() {
        var adapter = TouchSurfaceInputAdapter()

        _ = adapter.touchBegan(id: 1, point: CGPoint(x: 0, y: 0), timestamp: 0)
        _ = adapter.touchBegan(id: 2, point: CGPoint(x: 20, y: 0), timestamp: 0.01)
        _ = adapter.touchBegan(id: 3, point: CGPoint(x: 40, y: 0), timestamp: 0.02)
        _ = adapter.touchEnded(id: 1, point: CGPoint(x: 0, y: 0), timestamp: 0.08)
        _ = adapter.touchEnded(id: 2, point: CGPoint(x: 20, y: 0), timestamp: 0.09)
        let output = adapter.touchEnded(id: 3, point: CGPoint(x: 40, y: 0), timestamp: 0.1)

        XCTAssertEqual(output.commands, [.tap(kind: .middle)])
        XCTAssertEqual(output.semanticEvent, .tap(.middle))
    }

    func testCancelDuringSecondaryDragEmitsEnded() {
        var adapter = TouchSurfaceInputAdapter()

        _ = adapter.touchBegan(id: 1, point: CGPoint(x: 0, y: 0), timestamp: 0)
        _ = adapter.touchBegan(id: 2, point: CGPoint(x: 20, y: 0), timestamp: 0)
        _ = adapter.touchMoved(id: 1, point: CGPoint(x: 16, y: 0), timestamp: 0.24)
        _ = adapter.touchMoved(id: 2, point: CGPoint(x: 36, y: 0), timestamp: 0.25)
        let output = adapter.cancelAllTouches()

        XCTAssertEqual(output.commands, [.drag(state: .ended, button: .secondary, dx: 0, dy: 0)])
        XCTAssertEqual(output.semanticEvent, .dragEnded(.secondary))
    }
}
