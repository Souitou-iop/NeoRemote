import CoreGraphics
import Foundation

enum PlannedMouseEvent: Equatable {
    case move(to: CGPoint)
    case mouseDown(button: MouseButtonKind, point: CGPoint)
    case mouseUp(button: MouseButtonKind, point: CGPoint)
    case drag(button: MouseButtonKind, to: CGPoint)
    case scroll(deltaX: Int32, deltaY: Int32)
}

struct MouseEventPlanner {
    private(set) var currentPosition: CGPoint?
    private(set) var activeDragButton: MouseButtonKind?

    mutating func apply(_ command: RemoteCommand, positionProvider: () -> CGPoint) -> [PlannedMouseEvent] {
        if currentPosition == nil {
            currentPosition = positionProvider()
        }

        switch command {
        case .clientHello:
            return []

        case let .move(dx, dy):
            let point = translatedPoint(dx: dx, dy: dy)
            return [.move(to: point)]

        case let .tap(button):
            let point = currentPosition ?? positionProvider()
            return [
                .mouseDown(button: button, point: point),
                .mouseUp(button: button, point: point),
            ]

        case let .scroll(deltaX, deltaY):
            return [
                .scroll(
                    deltaX: Int32(deltaX.rounded()),
                    deltaY: Int32(deltaY.rounded())
                ),
            ]

        case let .drag(state, button, dx, dy):
            switch state {
            case .started:
                let point = currentPosition ?? positionProvider()
                activeDragButton = button
                return [.mouseDown(button: button, point: point)]

            case .changed:
                let point = translatedPoint(dx: dx, dy: dy)
                let button = activeDragButton ?? .primary
                activeDragButton = button
                return [.drag(button: button, to: point)]

            case .ended:
                let point = currentPosition ?? positionProvider()
                let button = activeDragButton ?? .primary
                activeDragButton = nil
                return [.mouseUp(button: button, point: point)]
            }

        case .heartbeat:
            return []
        }
    }

    private mutating func translatedPoint(dx: Double, dy: Double) -> CGPoint {
        let current = currentPosition ?? .zero
        let point = CGPoint(x: current.x + dx, y: current.y + dy)
        currentPosition = point
        return point
    }
}
