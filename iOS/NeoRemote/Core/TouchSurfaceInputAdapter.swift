import CoreGraphics
import Foundation

struct TouchSurfaceInputAdapter {
    private struct ActiveTouch {
        var startPoint: CGPoint
        var point: CGPoint
        var startTime: TimeInterval
    }

    private enum Phase: Equatable {
        case idle
        case singleTapCandidate
        case leftDragCandidate
        case leftDragActive
        case multiTouchCandidate
        case scrollActive
        case rightDragActive
        case middleTapCandidate
    }

    var moveMultiplier: Double = 1
    var scrollMultiplier: Double = 1
    var tapDistanceThreshold: CGFloat = 12
    var tapDurationThreshold: TimeInterval = 0.22
    var doubleTapWindow: TimeInterval = 0.32
    var dragDistanceThreshold: CGFloat = 14
    var scrollActivationDistance: CGFloat = 14
    var rightDragHoldDelay: TimeInterval = 0.18
    var scrollDominanceThreshold: CGFloat = 1.15

    init(settings: TouchSensitivitySettings = .default) {
        apply(settings: settings)
    }

    mutating func apply(settings: TouchSensitivitySettings) {
        let clamped = settings.clamped
        moveMultiplier = clamped.cursorSensitivity
        scrollMultiplier = clamped.swipeSensitivity
        scrollActivationDistance = 14 / CGFloat(clamped.swipeSensitivity)
    }

    private var activeTouches: [Int: ActiveTouch] = [:]
    private var phase: Phase = .idle
    private var lastTapTime: TimeInterval?
    private var sessionStartTime: TimeInterval?
    private var sessionStartCentroid: CGPoint?
    private var previousCentroid: CGPoint?
    private var maxTouchCount = 0
    private var singleTouchID: Int?

    mutating func touchBegan(id: Int, point: CGPoint, timestamp: TimeInterval) -> TouchSurfaceOutput {
        if activeTouches.isEmpty {
            beginSession(id: id, point: point, timestamp: timestamp)
        } else {
            activeTouches[id] = ActiveTouch(startPoint: point, point: point, startTime: timestamp)
            maxTouchCount = max(maxTouchCount, activeTouches.count)
            sessionStartCentroid = centroid()
            previousCentroid = sessionStartCentroid
            singleTouchID = nil

            switch activeTouches.count {
            case 2:
                phase = .multiTouchCandidate
            case 3:
                phase = .middleTapCandidate
            default:
                phase = .idle
            }
        }

        return .none
    }

    mutating func touchMoved(id: Int, point: CGPoint, timestamp: TimeInterval) -> TouchSurfaceOutput {
        guard let existing = activeTouches[id] else { return .none }
        let previousPoint = existing.point
        let previousCentroid = self.previousCentroid ?? centroid()

        activeTouches[id]?.point = point

        switch phase {
        case .singleTapCandidate:
            return handleSingleMove(from: previousPoint, to: point)

        case .leftDragCandidate:
            return handleLeftDragCandidateMove(id: id, point: point)

        case .leftDragActive:
            let dx = Double(point.x - previousPoint.x) * moveMultiplier
            let dy = Double(point.y - previousPoint.y) * moveMultiplier
            return TouchSurfaceOutput(
                commands: [.drag(state: .changed, button: .primary, dx: dx, dy: dy)],
                semanticEvent: .dragChanged(.primary)
            )

        case .multiTouchCandidate, .scrollActive, .rightDragActive:
            return handleTwoFingerMove(timestamp: timestamp, previousCentroid: previousCentroid)

        case .middleTapCandidate:
            if let start = sessionStartCentroid, start.distance(to: centroid()) > tapDistanceThreshold {
                phase = .idle
            }
            return .none

        case .idle:
            return .none
        }
    }

    mutating func touchEnded(id: Int, point _: CGPoint, timestamp: TimeInterval) -> TouchSurfaceOutput {
        guard activeTouches[id] != nil else { return .none }

        let dragEnd = dragEndOutputIfNeeded(endingTouchID: id)
        activeTouches.removeValue(forKey: id)

        if let dragEnd {
            resetIfSessionEnded()
            return dragEnd
        }

        guard activeTouches.isEmpty else { return .none }
        defer { resetSession() }

        let duration = timestamp - (sessionStartTime ?? timestamp)
        let movement = sessionStartCentroid.map { $0.distance(to: previousCentroid ?? $0) } ?? 0

        switch phase {
        case .singleTapCandidate, .leftDragCandidate:
            guard duration <= tapDurationThreshold, movement <= tapDistanceThreshold else { return .none }
            lastTapTime = timestamp
            return TouchSurfaceOutput(commands: [.tap(kind: .primary)], semanticEvent: .tap(.primary))

        case .multiTouchCandidate:
            guard maxTouchCount == 2, duration <= tapDurationThreshold, movement <= tapDistanceThreshold else { return .none }
            return TouchSurfaceOutput(commands: [.tap(kind: .secondary)], semanticEvent: .tap(.secondary))

        case .middleTapCandidate:
            guard maxTouchCount == 3, duration <= tapDurationThreshold, movement <= tapDistanceThreshold else { return .none }
            return TouchSurfaceOutput(commands: [.tap(kind: .middle)], semanticEvent: .tap(.middle))

        default:
            return .none
        }
    }

    mutating func cancelAllTouches() -> TouchSurfaceOutput {
        defer { resetSession() }

        switch phase {
        case .leftDragActive:
            return TouchSurfaceOutput(
                commands: [.drag(state: .ended, button: .primary, dx: 0, dy: 0)],
                semanticEvent: .dragEnded(.primary)
            )
        case .rightDragActive:
            return TouchSurfaceOutput(
                commands: [.drag(state: .ended, button: .secondary, dx: 0, dy: 0)],
                semanticEvent: .dragEnded(.secondary)
            )
        default:
            return .none
        }
    }

    private mutating func beginSession(id: Int, point: CGPoint, timestamp: TimeInterval) {
        activeTouches[id] = ActiveTouch(startPoint: point, point: point, startTime: timestamp)
        singleTouchID = id
        sessionStartTime = timestamp
        sessionStartCentroid = point
        previousCentroid = point
        maxTouchCount = 1

        if let lastTapTime, timestamp - lastTapTime <= doubleTapWindow {
            phase = .leftDragCandidate
        } else {
            phase = .singleTapCandidate
        }
    }

    private mutating func handleSingleMove(from previousPoint: CGPoint, to point: CGPoint) -> TouchSurfaceOutput {
        let dx = Double(point.x - previousPoint.x) * moveMultiplier
        let dy = Double(point.y - previousPoint.y) * moveMultiplier
        previousCentroid = point

        guard abs(dx) > 0.05 || abs(dy) > 0.05 else { return .none }
        return TouchSurfaceOutput(commands: [.move(dx: dx, dy: dy)], semanticEvent: nil)
    }

    private mutating func handleLeftDragCandidateMove(id: Int, point: CGPoint) -> TouchSurfaceOutput {
        guard id == singleTouchID, let touch = activeTouches[id] else { return .none }
        previousCentroid = point
        let distance = touch.startPoint.distance(to: point)

        guard distance > dragDistanceThreshold else { return .none }

        phase = .leftDragActive
        let dx = Double(point.x - touch.startPoint.x) * moveMultiplier
        let dy = Double(point.y - touch.startPoint.y) * moveMultiplier
        var commands: [RemoteCommand] = [.drag(state: .started, button: .primary, dx: 0, dy: 0)]
        if abs(dx) > 0.1 || abs(dy) > 0.1 {
            commands.append(.drag(state: .changed, button: .primary, dx: dx, dy: dy))
        }
        return TouchSurfaceOutput(commands: commands, semanticEvent: .dragStarted(.primary))
    }

    private mutating func handleTwoFingerMove(
        timestamp: TimeInterval,
        previousCentroid: CGPoint
    ) -> TouchSurfaceOutput {
        guard activeTouches.count == 2 else {
            phase = .idle
            return .none
        }

        let newCentroid = centroid()
        self.previousCentroid = newCentroid
        let dx = newCentroid.x - previousCentroid.x
        let dy = newCentroid.y - previousCentroid.y
        let sessionDistance = sessionStartCentroid.map { $0.distance(to: newCentroid) } ?? 0
        let sessionDuration = timestamp - (sessionStartTime ?? timestamp)

        if phase == .rightDragActive {
            return TouchSurfaceOutput(
                commands: [.drag(state: .changed, button: .secondary, dx: Double(dx) * moveMultiplier, dy: Double(dy) * moveMultiplier)],
                semanticEvent: .dragChanged(.secondary)
            )
        }

        if phase == .scrollActive {
            return scrollOutput(dx: dx, dy: dy)
        }

        guard sessionDistance > min(dragDistanceThreshold, scrollActivationDistance) else { return .none }

        if sessionDuration >= rightDragHoldDelay {
            phase = .rightDragActive
            var commands: [RemoteCommand] = [.drag(state: .started, button: .secondary, dx: 0, dy: 0)]
            if abs(dx) > 0.1 || abs(dy) > 0.1 {
                commands.append(.drag(state: .changed, button: .secondary, dx: Double(dx) * moveMultiplier, dy: Double(dy) * moveMultiplier))
            }
            return TouchSurfaceOutput(commands: commands, semanticEvent: .dragStarted(.secondary))
        }

        if sessionDistance >= scrollActivationDistance, abs(dy) >= abs(dx) * scrollDominanceThreshold {
            phase = .scrollActive
            return scrollOutput(dx: dx, dy: dy)
        }

        if sessionDistance >= scrollActivationDistance, abs(dx) >= abs(dy) * scrollDominanceThreshold {
            phase = .scrollActive
            return scrollOutput(dx: dx, dy: dy)
        }

        return .none
    }

    private func scrollOutput(dx: CGFloat, dy: CGFloat) -> TouchSurfaceOutput {
        let isHorizontal = abs(dx) >= abs(dy)
        let deltaX = isHorizontal ? Double(-dx) * scrollMultiplier : 0
        let deltaY = isHorizontal ? 0 : Double(-dy) * scrollMultiplier
        guard abs(deltaX) > 0.1 || abs(deltaY) > 0.1 else { return .none }
        return TouchSurfaceOutput(commands: [.scroll(deltaX: deltaX, deltaY: deltaY)], semanticEvent: .scrolling)
    }

    private func centroid() -> CGPoint {
        guard !activeTouches.isEmpty else { return .zero }
        let x = activeTouches.values.map(\.point.x).reduce(0, +) / CGFloat(activeTouches.count)
        let y = activeTouches.values.map(\.point.y).reduce(0, +) / CGFloat(activeTouches.count)
        return CGPoint(x: x, y: y)
    }

    private mutating func dragEndOutputIfNeeded(endingTouchID id: Int) -> TouchSurfaceOutput? {
        switch phase {
        case .leftDragActive where id == singleTouchID:
            phase = .idle
            return TouchSurfaceOutput(
                commands: [.drag(state: .ended, button: .primary, dx: 0, dy: 0)],
                semanticEvent: .dragEnded(.primary)
            )

        case .rightDragActive:
            phase = .idle
            return TouchSurfaceOutput(
                commands: [.drag(state: .ended, button: .secondary, dx: 0, dy: 0)],
                semanticEvent: .dragEnded(.secondary)
            )

        default:
            return nil
        }
    }

    private mutating func resetIfSessionEnded() {
        if activeTouches.isEmpty {
            resetSession()
        }
    }

    private mutating func resetSession() {
        activeTouches.removeAll()
        phase = .idle
        sessionStartTime = nil
        sessionStartCentroid = nil
        previousCentroid = nil
        maxTouchCount = 0
        singleTouchID = nil
    }
}
