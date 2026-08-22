import AVFoundation
import Foundation

/// On-device neural text-to-speech via sherpa-onnx (VITS/Piper), mirroring
/// Android's PocketTtsEngine.kt (core/tts/.../pocket/PocketTtsEngine.kt).
/// See SHERPA_ONNX_SETUP.md for the model and licensing story, and
/// PocketModelManager for why the model ships bundled here instead of
/// downloaded on first use like Android does.
final class PocketTTSEngine: TTSEngineProtocol {
    enum EngineError: LocalizedError {
        case modelNotBundled

        var errorDescription: String? {
            switch self {
            case .modelNotBundled:
                return "Pocket TTS voice model is not bundled with this build. " +
                    "Run third-party/sherpa-onnx/setup-ios.sh before building (see SHERPA_ONNX_SETUP.md)."
            }
        }
    }

    /// Filenames within PocketModelManager's bundled model directory - must
    /// match what third-party/sherpa-onnx/setup-ios.sh extracts, and the
    /// filenames baked into Android's PocketVoiceCatalog for the same voice.
    /// Internal (not private), per AGENTS.md's "pure helpers should be
    /// internal" convention, so PocketTTSEngineTests can pin these against
    /// setup-ios.sh/PocketVoiceCatalog drifting apart.
    static let modelFileName = "en_US-ljspeech-medium.onnx"
    static let tokensFileName = "tokens.txt"
    static let dataDirName = "espeak-ng-data"

    /// Overrides sherpa-onnx's library default (0.2) for inter-sentence
    /// pauses - mirrors Android's `PocketTtsEngine.SILENCE_SCALE`. See that
    /// constant's doc comment for the full rationale (no app-level sentence
    /// segmenter, whole chapters synthesized in one call) and what to try if
    /// 1.0 reads as too slow for long-form narration.
    static let silenceScale: Float = 1.0

    /// `xcodebuild test`'s CI Simulator has no real audio hardware and hangs
    /// on AVAudioSession/AVFoundation activation - see TTSEngineBridge's
    /// identical guard in DomainBridge.swift for the full story (two
    /// consecutive ~30-minute CI timeouts before this was found). Real usage
    /// (manual Simulator/device runs, TestFlight, App Store) never sets this.
    private static var isRunningUnderXCTest: Bool {
        ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
    }

    private let modelManager: PocketModelManager
    private let audioEngine = AVAudioEngine()
    private let playerNode = AVAudioPlayerNode()

    private var tts: SherpaOnnxOfflineTtsWrapper?
    private var playbackFormat: AVAudioFormat?
    private var isAudioEngineRunning = false

    init(modelManager: PocketModelManager = PocketModelManager()) {
        self.modelManager = modelManager
    }

    func initialize() async throws {
        // Model-path resolution runs before the XCTest guard deliberately -
        // it touches no audio hardware, so it's safe under test, and it's the
        // entire user-facing story for a broken build (setup-ios.sh not run).
        // Real usage is unaffected: modelDirectoryPath resolves either way,
        // so this ordering only changes what a *test* observes.
        guard let modelPath = modelManager.modelDirectoryPath else {
            throw EngineError.modelNotBundled
        }

        guard !Self.isRunningUnderXCTest else { return }

        let model = "\(modelPath)/\(Self.modelFileName)"
        let tokens = "\(modelPath)/\(Self.tokensFileName)"
        let dataDir = "\(modelPath)/\(Self.dataDirName)"

        let vits = sherpaOnnxOfflineTtsVitsModelConfig(
            model: model, lexicon: "", tokens: tokens, dataDir: dataDir)
        let modelConfig = sherpaOnnxOfflineTtsModelConfig(vits: vits, numThreads: 2, provider: "cpu")
        var ttsConfig = sherpaOnnxOfflineTtsConfig(model: modelConfig)
        let engine = SherpaOnnxOfflineTtsWrapper(config: &ttsConfig)
        tts = engine

        playbackFormat = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: Double(engine.sampleRate),
            channels: 1,
            interleaved: false)

        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
        try? AVAudioSession.sharedInstance().setActive(true)

        if let playbackFormat {
            audioEngine.attach(playerNode)
            audioEngine.connect(playerNode, to: audioEngine.mainMixerNode, format: playbackFormat)
        }
    }

    func speak(text: String, rate: Double) async {
        guard !Self.isRunningUnderXCTest,
              let tts, let format = playbackFormat, !text.isEmpty
        else { return }

        ensureAudioEngineRunning()
        playerNode.stop()
        playerNode.play()

        let context = PlaybackContext(node: playerNode, format: format)
        let arg = Unmanaged.passRetained(context).toOpaque()

        var genConfig = SherpaOnnxGenerationConfigSwift()
        genConfig.speed = Float(rate)
        genConfig.sid = 0
        genConfig.silenceScale = Self.silenceScale

        // generateWithConfig blocks the calling thread until synthesis
        // finishes, invoking the callback per chunk along the way - run it
        // off the main actor so speak() doesn't block the caller for the
        // full utterance. Any already-in-flight generation keeps running to
        // completion even after stop()/pause() - same documented tradeoff
        // as Android's PocketTtsEngine.generateChunks().
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            DispatchQueue.global(qos: .userInitiated).async {
                _ = tts.generateWithConfig(
                    text: text, config: genConfig, callback: Self.ttsCallback, arg: arg)
                Unmanaged<PlaybackContext>.fromOpaque(arg).release()
                continuation.resume()
            }
        }
    }

    private func ensureAudioEngineRunning() {
        guard !isAudioEngineRunning else { return }
        do {
            try audioEngine.start()
            isAudioEngineRunning = true
        } catch {
            // Playback simply won't produce sound; speak() still returns
            // normally rather than throwing, matching TTSEngineBridge's
            // "audio nicety, not a hard failure" tolerance.
        }
    }

    func stop() async {
        guard !Self.isRunningUnderXCTest else { return }
        playerNode.stop()
    }

    func pause() async {
        guard !Self.isRunningUnderXCTest else { return }
        playerNode.pause()
    }

    func resume() async {
        guard !Self.isRunningUnderXCTest else { return }
        playerNode.play()
    }

    /// C callback sherpa-onnx invokes per generated audio chunk. Must be a
    /// `@convention(c)` function (no captures), so per-call context (which
    /// AVAudioPlayerNode/format to feed) is threaded through via the
    /// `arg` pointer - same pattern sherpa-onnx's own tts-vits.swift example
    /// uses. Internal (not private), per AGENTS.md's "pure helpers should be
    /// internal" convention, so PocketTTSEngineTests can call this directly
    /// with a synthetic `arg` instead of only through a real `speak()` ->
    /// `generateWithConfig()` synthesis round trip.
    static let ttsCallback: TtsProgressCallbackWithArg = { samples, n, _, rawArg in
        guard let samples, n > 0, let rawArg else { return 1 }
        let context = Unmanaged<PlaybackContext>.fromOpaque(rawArg).takeUnretainedValue()
        let floatSamples = [Float](UnsafeBufferPointer(start: samples, count: Int(n)))
        if let buffer = pcmBuffer(from: floatSamples, format: context.format) {
            context.node.scheduleBuffer(buffer, completionHandler: nil)
        }
        return 1 // continue generating
    }

    /// Internal (not private) so PocketTTSEngineTests can exercise this pure
    /// transform directly, per AGENTS.md's "pure helpers should be internal"
    /// convention.
    static func pcmBuffer(from samples: [Float], format: AVAudioFormat) -> AVAudioPCMBuffer? {
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count)),
              let channelData = buffer.floatChannelData
        else { return nil }
        buffer.frameLength = AVAudioFrameCount(samples.count)
        samples.withUnsafeBufferPointer { ptr in
            guard let baseAddress = ptr.baseAddress else { return }
            channelData[0].update(from: baseAddress, count: samples.count)
        }
        return buffer
    }
}

/// Per-`speak()`-call context passed across the C callback boundary (see
/// `ttsCallback` above) - retained for the duration of one generate call,
/// then released once it returns. Internal (not private) so
/// PocketTTSEngineTests can construct one directly to drive `ttsCallback`.
final class PlaybackContext {
    let node: AVAudioPlayerNode
    let format: AVAudioFormat

    init(node: AVAudioPlayerNode, format: AVAudioFormat) {
        self.node = node
        self.format = format
    }
}
