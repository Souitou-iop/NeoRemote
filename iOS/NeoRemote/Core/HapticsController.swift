import UIKit

final class HapticsController {
    var isEnabled = true

    private let tapGenerator = UIImpactFeedbackGenerator(style: .light)
    private let moveGenerator = UISelectionFeedbackGenerator()
    private let dragStartGenerator = UIImpactFeedbackGenerator(style: .medium)
    private let dragTickGenerator = UISelectionFeedbackGenerator()
    private let connectionGenerator = UINotificationFeedbackGenerator()
    private var lastMoveTickAt = Date.distantPast
    private var lastDragTickAt = Date.distantPast

    func prepare() {
        guard isEnabled else { return }
        tapGenerator.prepare()
        moveGenerator.prepare()
        dragStartGenerator.prepare()
        dragTickGenerator.prepare()
        connectionGenerator.prepare()
    }

    func playMoveTick() {
        guard isEnabled, canPlayThrottledTick(lastTickAt: &lastMoveTickAt, minimumInterval: 0.08) else { return }
        moveGenerator.selectionChanged()
        moveGenerator.prepare()
    }

    func playTap() {
        guard isEnabled else { return }
        tapGenerator.impactOccurred(intensity: 0.9)
        tapGenerator.prepare()
    }

    func playDragStart() {
        guard isEnabled else { return }
        dragStartGenerator.impactOccurred(intensity: 0.85)
        dragStartGenerator.prepare()
    }

    func playDragTick() {
        guard isEnabled, canPlayThrottledTick(lastTickAt: &lastDragTickAt, minimumInterval: 0.06) else { return }
        dragTickGenerator.selectionChanged()
        dragTickGenerator.prepare()
    }

    func playConnectionStateChange(success: Bool) {
        guard isEnabled else { return }
        connectionGenerator.notificationOccurred(success ? .success : .warning)
        connectionGenerator.prepare()
    }

    private func canPlayThrottledTick(lastTickAt: inout Date, minimumInterval: TimeInterval) -> Bool {
        let now = Date()
        guard now.timeIntervalSince(lastTickAt) >= minimumInterval else { return false }
        lastTickAt = now
        return true
    }
}
