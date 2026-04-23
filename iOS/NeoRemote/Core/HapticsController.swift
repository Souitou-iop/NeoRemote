import UIKit

final class HapticsController {
    var isEnabled = true

    private let tapGenerator = UIImpactFeedbackGenerator(style: .light)
    private let dragStartGenerator = UIImpactFeedbackGenerator(style: .medium)
    private let dragTickGenerator = UISelectionFeedbackGenerator()
    private let connectionGenerator = UINotificationFeedbackGenerator()

    func prepare() {
        guard isEnabled else { return }
        tapGenerator.prepare()
        dragStartGenerator.prepare()
        dragTickGenerator.prepare()
        connectionGenerator.prepare()
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
        guard isEnabled else { return }
        dragTickGenerator.selectionChanged()
        dragTickGenerator.prepare()
    }

    func playConnectionStateChange(success: Bool) {
        guard isEnabled else { return }
        connectionGenerator.notificationOccurred(success ? .success : .warning)
        connectionGenerator.prepare()
    }
}
