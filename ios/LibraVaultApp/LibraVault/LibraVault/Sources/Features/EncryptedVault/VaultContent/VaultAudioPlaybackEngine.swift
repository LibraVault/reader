import AVFoundation
import Foundation

/// `AudioPlaybackEngine`'s counterpart for vault audio: plays from an
/// already-decrypted, in-memory `Data` buffer (`VaultStore.readFullContent`)
/// rather than a file URL. A separate, parallel type rather than a `Data`
/// overload added to `AudioPlaybackEngine.swift` itself — see
/// `VaultEPUBParser`'s doc comment for why #203 keeps every vault content
/// adapter alongside, not inside, the existing non-vault reader/player files.
///
/// Whole-file-in-RAM is the MVP tier here (matches Android's own fallback
/// path, `VaultMemfdFallback`, not its primary lazy one) — fine for typical
/// audiobook sizes, acceptable even up to the multi-hundred-MB/1GB+ range
/// `AVAudioPlayer` itself can hold in memory. A true streaming path
/// (`AVPlayer` + a custom `AVAssetResourceLoaderDelegate` serving ranged
/// reads from `VaultFileReader.readAt`) is a tracked follow-up (#203), not
/// required for this MVP.
///
/// Deliberately does *not* pass a `fileTypeHint` to `AVAudioPlayer(data:)` —
/// `VaultManifestEntry.format` stores `MediaFormat`'s case name, which
/// collapses both `.m4a` and `.aac` *file extensions* into the single format
/// case `.aac` (see `LibraryFileScanner.extensionFormats`), so there is no
/// reliable extension to hint with for that case. `AVAudioPlayer(data:)`
/// without a hint sniffs the container from the data's own header instead,
/// which works for every format this app imports and sidesteps that
/// ambiguity entirely.
final class VaultAudioPlaybackEngine: NSObject {
    private var player: AVAudioPlayer?
    private var progressTimer: Timer?

    /// Called ~4x/second while playing with the player's real elapsed/duration.
    var onProgress: ((_ elapsed: Double, _ duration: Double) -> Void)?
    /// Called when playback finishes on its own (not via an explicit stop()).
    var onFinished: (() -> Void)?

    var isPlaying: Bool { player?.isPlaying ?? false }
    var duration: Double { player?.duration ?? 0 }

    var elapsed: Double {
        get { player?.currentTime ?? 0 }
        set { player?.currentTime = max(0, min(newValue, duration)) }
    }

    var volume: Float {
        get { player?.volume ?? 1.0 }
        set { player?.volume = newValue }
    }

    /// Loads decrypted audio bytes and readies them for playback (via
    /// `prepareToPlay`) without starting output — mirrors
    /// `AudioPlaybackEngine.load(fileURL:rate:)`'s split from `play`, for the
    /// same reason: callers/tests can inspect real duration without
    /// necessarily starting audio. Throws if `data` isn't a decodable audio
    /// container.
    func load(data: Data, rate: Float) throws {
        stop()
        configureAudioSession()
        let newPlayer = try AVAudioPlayer(data: data)
        newPlayer.delegate = self
        newPlayer.enableRate = true
        newPlayer.rate = rate
        newPlayer.prepareToPlay()
        player = newPlayer
    }

    /// See `AudioPlaybackEngine.configureAudioSession`'s doc comment — same
    /// `.playback` category so vault audiobook playback keeps running when
    /// the device locks or the app backgrounds.
    private func configureAudioSession() {
        guard !Self.isRunningUnderXCTest else { return }
        try? AVAudioSession.sharedInstance().setCategory(.playback)
        try? AVAudioSession.sharedInstance().setActive(true)
    }

    /// See `AudioPlaybackEngine.isRunningUnderXCTest`'s doc comment — real
    /// `AVAudioSession` activation hangs in the CI Simulator, which has no
    /// audio hardware.
    private static var isRunningUnderXCTest: Bool {
        ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
    }

    /// Loads `data` and starts playing it from the beginning.
    func play(data: Data, rate: Float) throws {
        try load(data: data, rate: rate)
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

extension VaultAudioPlaybackEngine: AVAudioPlayerDelegate {
    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        stopProgressTimer()
        onFinished?()
    }
}
