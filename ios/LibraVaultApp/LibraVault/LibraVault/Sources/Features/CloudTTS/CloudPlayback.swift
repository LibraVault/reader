import AVFoundation
import Foundation

/// Plays synthesized cloud-TTS audio bytes and reports when playback finishes — the
/// seam `CloudTtsEngine` awaits on. Mirrors Android's `CloudPlayback`/
/// `MediaPlayerCloudPlayback` (a protocol wrapping the platform's media player, kept
/// separate from the engine itself for the same fakeability reasons).
protocol CloudPlayback: AnyObject {
    /// Loads and plays `data` to completion, suspending until playback finishes
    /// naturally OR `stop()` is called (which resolves this normally, not as an
    /// error — see `AVAudioPlayerCloudPlayback.stop()`'s doc comment). Throws if
    /// `data` can't be decoded as audio, or if playback fails to start.
    func play(data: Data) async throws
    func pause()
    func resume()
    func stop()
}

/// Real, `AVAudioPlayer`-backed `CloudPlayback`.
///
/// Every vendor adapter explicitly requests MP3 from its vendor (see each
/// `Vendors/*Adapter.swift`'s `synthesize()` — `audioEncoding: "MP3"`,
/// `X-Microsoft-OutputFormat: audio-16khz-32kbitrate-mono-mp3`, `OutputFormat: "mp3"`,
/// or vendor default), so the container is always known here rather than sniffed from
/// magic bytes the way `VaultAudioPlaybackEngine.fileTypeHint(for:)` has to for
/// arbitrary user-imported audiobook files. An explicit `fileTypeHint` is still
/// required, though, not optional — omitting it entirely was a real, CI-caught bug on
/// `VaultAudioPlaybackEngine` (`duration` silently reporting `0` instead of throwing;
/// see that type's doc comment) that this reuses the fix for, rather than
/// re-discovering the same gap here.
///
/// Split into `load(data:)` (safe to run under test — pure decode, no audio hardware)
/// and the actual activate-session-and-play path (guarded by `isRunningUnderXCTest`,
/// mirroring `VaultAudioPlaybackEngine`'s identical split and its own doc comment on
/// why: `AVAudioSession` activation hangs indefinitely in the CI Simulator, which has
/// no audio hardware — decoding a byte buffer into an `AVAudioPlayer` does not).
final class AVAudioPlayerCloudPlayback: NSObject, CloudPlayback {
    private var player: AVAudioPlayer?
    private var finishContinuation: CheckedContinuation<Void, Error>?
    /// Guards `finishContinuation` against a genuine race: `stop()` (called from
    /// `CloudTtsEngine`, on the main actor) and `audioPlayerDidFinishPlaying`
    /// (called by `AVAudioPlayer` on its own internal thread, not guaranteed to be
    /// the main thread) can both try to resolve the same continuation concurrently.
    /// Resolving a `CheckedContinuation` twice is a fatal error, not just a logic
    /// bug — mirrors Android's `MediaPlayerCloudPlayback` needing the identical fix
    /// (`synchronized(lock)` on every method) for the same underlying reason,
    /// applied here proactively rather than after a crash report surfaces it.
    private let lock = NSLock()

    private static var isRunningUnderXCTest: Bool {
        ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
    }

    /// Throws if `data` isn't a decodable MP3 — safe to call under test.
    func load(data: Data) throws -> AVAudioPlayer {
        let newPlayer = try AVAudioPlayer(data: data, fileTypeHint: AVFileType.mp3.rawValue)
        newPlayer.delegate = self
        return newPlayer
    }

    func play(data: Data) async throws {
        let newPlayer = try load(data: data)
        guard !Self.isRunningUnderXCTest else { return }

        configureAudioSession()
        player = newPlayer

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            lock.lock()
            finishContinuation = continuation
            lock.unlock()
            guard newPlayer.play() else {
                resolveContinuation { $0.resume(throwing: CloudPlaybackError.failedToStart) }
                return
            }
        }
    }

    func pause() {
        guard !Self.isRunningUnderXCTest else { return }
        player?.pause()
    }

    func resume() {
        guard !Self.isRunningUnderXCTest else { return }
        player?.play()
    }

    /// Resolves any pending `play(data:)` continuation as a plain success (NOT an
    /// error) — `stop()` is a deliberate user action, not a playback failure.
    /// `CloudTtsEngine.stop()` separately cancels its own in-flight `Task`, which is
    /// what actually distinguishes "genuinely finished" from "stopped early" for the
    /// caller; this just has to unblock the suspended `await` either way so
    /// `performSpeak` can proceed to notice that cancellation and return without
    /// falling back to the on-device engine.
    func stop() {
        guard !Self.isRunningUnderXCTest else { return }
        player?.stop()
        player = nil
        resolveContinuation { $0.resume() }
    }

    private func configureAudioSession() {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
        try? AVAudioSession.sharedInstance().setActive(true)
    }

    private func resolveContinuation(_ action: (CheckedContinuation<Void, Error>) -> Void) {
        lock.lock()
        let continuation = finishContinuation
        finishContinuation = nil
        lock.unlock()
        guard let continuation else { return }
        action(continuation)
    }
}

extension AVAudioPlayerCloudPlayback: AVAudioPlayerDelegate {
    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        resolveContinuation { $0.resume() }
    }

    func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        resolveContinuation { $0.resume(throwing: error ?? CloudPlaybackError.decodeError) }
    }
}

enum CloudPlaybackError: LocalizedError {
    case failedToStart
    case decodeError

    var errorDescription: String? {
        switch self {
        case .failedToStart: return "Cloud TTS audio playback failed to start"
        case .decodeError: return "Cloud TTS audio could not be decoded"
        }
    }
}
