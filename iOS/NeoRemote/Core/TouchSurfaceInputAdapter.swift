import CoreGraphics
import Foundation

struct TouchSurfaceInputAdapter {
    private struct ActiveTouch {
        var startPoint: CGPoint
        var point: CGPoint
        var startTime: TimeInterval
    }

    var moveMultiplier: Double = 1
    var scrollMultiplier: Double = 1
    var tapDistanceThreshold: CGFloat = 12
    var tapDurationThreshold: TimeInterval = 0.22
    var doubleTapWindow: TimeInterval = 0.32

    private var activeTouches: [Int: ActiveTouch] = [:]
    private var lastTapTime: TimeInterval?
    private var dragCandidateId: Int?
    private var dragActiveId: Int?
    private var multiTouchSession = false

    mutating func touchBegan(id: Int, point: CGPoint, timestamp: TimeInterval) -> TouchSurfaceOutput {
        if activeTouches.isEmpty, let lastTapTime, timestamp - lastTapTime <= doubleTapWindow {
            dragCandidateId = id
        }

        activeTouches[id] = ActiveTouch(startPoint: point, point: point, startTime: timestamp)

        if activeTouches.count >= 2 {
            multiTouchSession = true
        }

        return .none
    }

    mutating func touchMoved(id: Int, point: CGPoint, timestamp _: TimeInterval) -> TouchSurfaceOutput {
        guard let existing = activeTouches[id] else { return .none }

        let previousAverageY = activeTouches.isEmpty ? 0 : activeTouches.values.map(\.point.y).reduce(0, +) / CGFloat(activeTouches.count)
        let previousPoint = existing.point

        activeTouches[id]?.point = point

        if activeTouches.count >= 2 {
            multiTouchSession = true
            let newAverageY = activeTouches.values.map(\.point.y).reduce(0, +) / CGFloat(activeTouches.count)
            let deltaY = Double(previousAverageY - newAverageY) * scrollMultiplier

            guard abs(deltaY) > 0.1 else { return .none }
            return TouchSurfaceOutput(
                commands: [.scroll(deltaY: deltaY)],
                semanticEvent: .scrolling
            )
        }

        let dx = Double(point.x - previousPoint.x) * moveMultiplier
        let dy = Double(point.y - previousPoint.y) * moveMultiplier
        let totalDistance = existing.startPoint.distance(to: point)

        if dragActiveId == id {
            return TouchSurfaceOutput(
                commands: [.drag(state: .changed, dx: dx, dy: dy)],
                semanticEvent: .dragChanged
            )
        }

        if dragCandidateId == id, totalDistance > tapDistanceThreshold {
            dragCandidateId = nil
            dragActiveId = id

            var commands: [RemoteCommand] = [.drag(state: .started, dx: 0, dy: 0)]
            if abs(dx) > 0.1 || abs(dy) > 0.1 {
                commands.append(.drag(state: .changed, dx: dx, dy: dy))
            }

            return TouchSurfaceOutput(commands: commands, semanticEvent: .dragStarted)
        }

        guard abs(dx) > 0.05 || abs(dy) > 0.05 else {
            return .none
        }

        return TouchSurfaceOutput(
            commands: [.move(dx: dx, dy: dy)],
            semanticEvent: .moving
        )
    }

    mutating func touchEnded(id: Int, point: CGPoint, timestamp: TimeInterval) -> TouchSurfaceOutput {
        guard let touch = activeTouches[id] else { return .none }

        activeTouches.removeValue(forKey: id)

        if activeTouches.isEmpty {
            multiTouchSession = false
        }

        if dragActiveId == id {
            dragActiveId = nil
            dragCandidateId = nil
            return TouchSurfaceOutput(
                commands: [.drag(state: .ended, dx: 0, dy: 0)],
                semanticEvent: .dragEnded
            )
        }

        let duration = timestamp - touch.startTime
        let distance = touch.startPoint.distance(to: point)

        if dragCandidateId == id {
            dragCandidateId = nil
        }

        guard !multiTouchSession else {
            return .none
        }

        guard duration <= tapDurationThreshold, distance <= tapDistanceThreshold else {
            return .none
        }

        lastTapTime = timestamp
        return TouchSurfaceOutput(
            commands: [.tap(kind: .primary)],
            semanticEvent: .tap
        )
    }

    mutating func cancelAllTouches() -> TouchSurfaceOutput {
        defer {
            activeTouches.removeAll()
            dragCandidateId = nil
            dragActiveId = nil
            multiTouchSession = false
        }

        guard dragActiveId != nil else { return .none }
        return TouchSurfaceOutput(
            commands: [.drag(state: .ended, dx: 0, dy: 0)],
            semanticEvent: .dragEnded
        )
    }
}
