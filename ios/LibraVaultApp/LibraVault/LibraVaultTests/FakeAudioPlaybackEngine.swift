import Foundation
@testable import LibraVault

/// A test double for AudioPlaybackEngineProtocol with no AVFoundation calls at all —
/// real AVAudioPlayer/AVAudioSession use hangs in the headless CI Simulator (see
/// AudioPlaybackEngineTests' doc comment), so AppState's audio-format playback branch
/// can only be exercised in tests through this fake, injected via AppState's
/// `audioEngine:` init parameter.
final class FakeAudioPlaybackEngine: AudioPlaybackEngineProtocol {
    var onProgress: ((_ elapsed: Double, _ duration: Double) -> Void)?
    var onFinished: (() -> Void)?

    var isPlaying = false
    /// Returned as the "real" file duration after a successful play()/load() —
    /// configurable per test since there's no real file being decoded.
    var duration: Double = 100
    var elapsed: Double = 0

    /// Set before calling play() to make it throw, exercising AppState's
    /// unplayable-file error path.
    var errorToThrowOnPlay: Error?

    private(set) var loadedFileURL: URL?
    private(set) var playedFileURL: URL?
    private(set) var playedRate: Float?
    private(set) var setRateCalls: [Float] = []
    private(set) var pauseCallCount = 0
    private(set) var resumeCallCount = 0
    private(set) var stopCallCount = 0

    func load(fileURL: URL, rate: Float) throws {
        loadedFileURL = fileURL
    }

    func play(fileURL: URL, rate: Float) throws {
        if let errorToThrowOnPlay {
            throw errorToThrowOnPlay
        }
        playedFileURL = fileURL
        playedRate = rate
        elapsed = 0
        isPlaying = true
    }

    func setRate(_ rate: Float) {
        setRateCalls.append(rate)
    }

    func pause() {
        pauseCallCount += 1
        isPlaying = false
    }

    func resume() {
        resumeCallCount += 1
        isPlaying = true
    }

    func stop() {
        stopCallCount += 1
        isPlaying = false
    }
}
