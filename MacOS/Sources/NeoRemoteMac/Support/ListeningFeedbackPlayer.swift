import AppKit

protocol ListeningFeedbackPlaying: AnyObject {
    func play(enabled: Bool)
}

final class ListeningFeedbackPlayer: ListeningFeedbackPlaying {
    func play(enabled: Bool) {
        let soundName: NSSound.Name = enabled ? .init("Glass") : .init("Basso")
        NSSound(named: soundName)?.play()
    }
}
