import AVFoundation
import Foundation

/// Real audio-file playback for audiobook-format books, replacing the previous
/// TTS-narrates-fake-text simulation. Wraps AVAudioPlayer directly rather than
/// AVPlayer/AVPlayerItem — audiobook files are local, already-downloaded files (no
/// streaming involved), and AVAudioPlayer's synchronous, plain-property API
/// (currentTime/duration, no KVO/async item-status ceremony) is a better fit here.
///
/// Audiobooks are treated as a single chapter for now — real per-chapter markers
/// embedded in some m4b files (AVAsset chapter metadata) aren't extracted yet; that's
/// a real future refinement, not a mock-content concern (skip-forward/backward
/// already does genuine seeking within the file, which is the primary way listeners
/// navigate an audiobook day to day).
final class AudioPlaybackEngine: NSObject {
    private var player: AVAudioPlayer?
    private var progressTimer: Timer?

    /// Called ~4x/second while playing with the player's real elapsed/duration.
    var onProgress: ((_ elapsed: Double, _ duration: Double) -> Void)?
    /// Called when the file finishes playing on its own (not via an explicit stop()).
    var onFinished: (() -> Void)?

    var isPlaying: Bool { player?.isPlaying ?? false }
    var duration: Double { player?.duration ?? 0 }

    var elapsed: Double {
        get { player?.currentTime ?? 0 }
        set { player?.currentTime = max(0, min(newValue, duration)) }
    }

    /// Loads a file and readies it for playback (via `prepareToPlay`) without
    /// actually starting audio output — split out from `play(fileURL:rate:)` so
    /// callers (and tests) can inspect a loaded file's real duration without
    /// necessarily starting playback. Throws if the file can't be opened (missing,
    /// corrupt, unsupported codec).
    func load(fileURL: URL, rate: Float) throws {
        stop()
        let newPlayer = try AVAudioPlayer(contentsOf: fileURL)
        newPlayer.delegate = self
        newPlayer.enableRate = true
        newPlayer.rate = rate
        newPlayer.prepareToPlay()
        player = newPlayer
    }

    /// Loads a new file and starts playing it from the beginning.
    func play(fileURL: URL, rate: Float) throws {
        try load(fileURL: fileURL, rate: rate)
        player?.play()
        startProgressTimer()
    }

    func setRate(_ rate: Float) {
        player?.rate = rate
    }

    func pause() {
        player?.pause()
        stopProgressTimer()
    }

    func resume() {
        player?.play()
        startProgressTimer()
    }

    func stop() {
        player?.stop()
        player = nil
        stopProgressTimer()
    }

    private func startProgressTimer() {
        stopProgressTimer()
        progressTimer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            guard let self, let player = self.player else { return }
            self.onProgress?(player.currentTime, player.duration)
        }
    }

    private func stopProgressTimer() {
        progressTimer?.invalidate()
        progressTimer = nil
    }
}

extension AudioPlaybackEngine: AVAudioPlayerDelegate {
    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        stopProgressTimer()
        onFinished?()
    }
}
