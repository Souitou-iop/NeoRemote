import UIKit

final class HapticsController {
    private let tapGenerator = UIImpactFeedbackGenerator(style: .light)
    private let dragStartGenerator = UIImpactFeedbackGenerator(style: .medium)
    private let dragTickGenerator = UISelectionFeedbackGenerator()
    private let connectionGenerator = UINotificationFeedbackGenerator()

    func prepare() {
        tapGenerator.prepare()
        dragStartGenerator.prepare()
        dragTickGenerator.prepare()
        connectionGenerator.prepare()
    }

    func playTap() {
        tapGenerator.impactOccurred(intensity: 0.9)
        tapGenerator.prepare()
    }

    func playDragStart() {
        dragStartGenerator.impactOccurred(intensity: 0.85)
        dragStartGenerator.prepare()
    }

    func playDragTick() {
        dragTickGenerator.selectionChanged()
        dragTickGenerator.prepare()
    }

    func playConnectionStateChange(success: Bool) {
        connectionGenerator.notificationOccurred(success ? .success : .warning)
        connectionGenerator.prepare()
    }
}
