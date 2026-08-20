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
/// Passes an explicit `fileTypeHint` to `AVAudioPlayer(data:fileTypeHint:)`,
/// determined by sniffing `data`'s own magic bytes rather than trusting
/// `VaultManifestEntry.format` — that string collapses both `.m4a` and
/// `.aac` *file extensions* into the single format case `.aac` (see
/// `LibraryFileScanner.extensionFormats`), so it can't be trusted as a
/// reliable hint on its own. The original design here omitted the hint
/// entirely, on the assumption `AVAudioPlayer(data:)` sniffs the container
/// from the data's own header just as reliably without one — that turned
/// out to be false: without a hint, `duration` (and therefore
/// `elapsed`/seeking) silently reports `0` instead of throwing, caught by
/// `VaultAudioPlaybackEngineTests`/`VaultPlayerViewModelTests` failing in CI
/// (the exact gap those tests exist to catch — see AGENTS.md's "Prove a new
/// test can fail"). Byte-sniffing sidesteps the `.m4a`/`.aac` ambiguity too,
/// since it reads the real container rather than the collapsed format
/// string.
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
        let newPlayer = try AVAudioPlayer(data: data, fileTypeHint: Self.fileTypeHint(for: data))
        newPlayer.delegate = self
        newPlayer.enableRate = true
        newPlayer.rate = rate
        newPlayer.prepareToPlay()
        player = newPlayer
    }

    /// Identifies `data`'s real container from its own magic bytes, for
    /// `AVAudioPlayer(data:fileTypeHint:)` — see this type's doc comment for
    /// why an explicit hint is required, not optional. Covers every
    /// container `AVAudioPlayer` natively decodes among the formats this app
    /// imports (`LibraryFileScanner`'s `MediaFormat.isAudio` cases); returns
    /// `nil` for anything unrecognized so `AVAudioPlayer` falls back to its
    /// own (unreliable, per the doc comment) sniffing rather than this
    /// method guessing wrong. `internal`, not `private` — per AGENTS.md's
    /// "pure helpers should be internal, not private" guidance — so
    /// `VaultAudioPlaybackEngineTests` can exercise each container branch
    /// directly against small synthetic byte arrays, instead of only via
    /// `load(data:rate:)` and a real fixture per format.
    static func fileTypeHint(for data: Data) -> String? {
        guard data.count >= 12 else { return nil }
        let bytes = [UInt8](data.prefix(12))

        // RIFF....WAVE
        if bytes[0...3].elementsEqual([0x52, 0x49, 0x46, 0x46]), bytes[8...11].elementsEqual([0x57, 0x41, 0x56, 0x45]) {
            return AVFileType.wav.rawValue
        }
        // fLaC
        if bytes[0...3].elementsEqual([0x66, 0x4C, 0x61, 0x43]) {
            return AVFileType.flac.rawValue
        }
        // ....ftyp — MPEG-4 container (.m4a/.m4b)
        if bytes[4...7].elementsEqual([0x66, 0x74, 0x79, 0x70]) {
            return AVFileType.m4a.rawValue
        }
        // ID3 tag, or a raw MPEG frame sync (11 set bits: 0xFF Ex/Fx)
        if bytes[0...2].elementsEqual([0x49, 0x44, 0x33]) || (bytes[0] == 0xFF && (bytes[1] & 0xE0) == 0xE0) {
            return AVFileType.mp3.rawValue
        }
        return nil
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
