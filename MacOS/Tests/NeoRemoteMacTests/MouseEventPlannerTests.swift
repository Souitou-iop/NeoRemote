import CoreGraphics
import XCTest
@testable import NeoRemoteMac

final class MouseEventPlannerTests: XCTestCase {
    func testMoveCommandMapsToMovedCursorPosition() {
        var planner = MouseEventPlanner()

        let events = planner.apply(.move(dx: 20, dy: 10)) {
            CGPoint(x: 100, y: 100)
        }

        XCTAssertEqual(events, [.move(to: CGPoint(x: 120, y: 90))])
    }

    func testTapCommandMapsToMouseDownAndMouseUp() {
        var planner = MouseEventPlanner()

        let events = planner.apply(.tap(kind: .primary)) {
            CGPoint(x: 40, y: 60)
        }

        XCTAssertEqual(
            events,
            [
                .mouseDown(button: .primary, point: CGPoint(x: 40, y: 60)),
                .mouseUp(button: .primary, point: CGPoint(x: 40, y: 60)),
            ]
        )
    }

    func testDragLifecycleMapsToDownDragUp() {
        var planner = MouseEventPlanner()

        let start = planner.apply(.drag(state: .started, dx: 0, dy: 0)) {
            CGPoint(x: 30, y: 30)
        }
        let change = planner.apply(.drag(state: .changed, dx: 5, dy: -4)) {
            CGPoint(x: 30, y: 30)
        }
        let end = planner.apply(.drag(state: .ended, dx: 0, dy: 0)) {
            CGPoint(x: 30, y: 30)
        }

        XCTAssertEqual(start, [.mouseDown(button: .primary, point: CGPoint(x: 30, y: 30))])
        XCTAssertEqual(change, [.drag(button: .primary, to: CGPoint(x: 35, y: 34))])
        XCTAssertEqual(end, [.mouseUp(button: .primary, point: CGPoint(x: 35, y: 34))])
    }
}
