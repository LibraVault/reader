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
    // "high" tier, not "medium" - swapped 2026-08-22 in response to real
    // TestFlight feedback describing the voice as robotic. Same LJSpeech
    // (public-domain) training data/license as medium, just a bigger
    // checkpoint - see SHERPA_ONNX_SETUP.md's "Updating the voice model".
    static let modelFileName = "en_US-ljspeech-high.onnx"
    static let tokensFileName = "tokens.txt"
    static let dataDirName = "espeak-ng-data"

    /// sherpa-onnx's own default (0.2) compresses inter-sentence pauses to
    /// 20% of what the VITS model itself predicts. That's barely noticeable
    /// for a short example sentence, but `speak` hands over an entire
    /// chapter in one synthesis call, so every sentence boundary in the
    /// chapter gets that same rushed pause. Real TestFlight feedback
    /// described the resulting narration as "robotic"; 1.0 (the model's own
    /// predicted pause length, unscaled) reads as natural sentence-to-
    /// sentence pacing for long-form narration instead. Mirrors Android's
    /// PocketTtsEngine.SILENCE_SCALE. Tune down if on-device listening finds
    /// full-length pauses too slow. Internal, not private, so
    /// PocketTTSEngineTests can pin this value the same way it pins the
    /// model filenames above.
    static let silenceScale: Float = 1.0

    /// Extra silence spliced between two separately-synthesized chunks for
    /// `.paragraph`/`.sceneBreak` pause hints (issue #638). `.sentence` gets
    /// no entry here — it doesn't get a hard cut at all, see
    /// `pauseGroups(for:)`. Values chosen to roughly match
    /// `SSMLRenderer`'s break durations for the system engine, so switching
    /// between the two engines feels consistent (see its own doc comment).
    static let paragraphPauseSeconds: Double = 0.3
    static let sceneBreakPauseSeconds: Double = 0.9

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

        await synthesize(text: text, rate: rate, tts: tts, format: format)
    }

    /// Pause-splicing (#638): `NarrationSegment`'s `pauseBefore` hints are
    /// the only lever PocketTTS's bound API has any way to honor at all —
    /// there's no phoneme-level markup or per-call inline pause API, so
    /// `.paragraph`/`.sceneBreak` become a silent chunk spliced between two
    /// separately-synthesized `generateWithConfig()` calls instead
    /// (`PocketPlayback`'s Android equivalent streams chunk-by-chunk the
    /// same way, once its own segment plumbing lands). `.emphasis`/`.quote`
    /// have no realizable lever here at all — a permanent capability gap,
    /// not a v1 scoping choice — so their text is narrated exactly as
    /// plain text would be; only the pause hint before a segment does
    /// anything on this engine.
    func speak(segments: [NarrationSegment], rate: Double) async {
        guard !Self.isRunningUnderXCTest,
              let tts, let format = playbackFormat, !segments.isEmpty
        else { return }

        ensureAudioEngineRunning()
        playerNode.stop()
        playerNode.play()

        for group in Self.pauseGroups(for: segments) {
            let seconds = Self.silenceSeconds(for: group.pauseBefore)
            if let silence = Self.silenceBuffer(seconds: seconds, format: format) {
                playerNode.scheduleBuffer(silence, completionHandler: nil)
            }
            await synthesize(text: group.text, rate: rate, tts: tts, format: format)
        }
    }

    /// Shared per-chunk synthesis call used by both `speak(text:rate:)` and
    /// `speak(segments:rate:)` — the latter just calls this once per
    /// pause-separated group instead of once for the whole chapter.
    private func synthesize(
        text: String, rate: Double, tts: SherpaOnnxOfflineTtsWrapper, format: AVAudioFormat
    ) async {
        guard !text.isEmpty else { return }

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

    /// One `generateWithConfig()` call's worth of text, plus the pause that
    /// should precede it. Internal (not private) so `PocketTTSEngineTests`
    /// can pin the grouping/joining behaviour directly, per AGENTS.md's
    /// "pure helpers should be internal" convention.
    struct PauseGroup: Equatable {
        let text: String
        let pauseBefore: NarrationSegment.PauseHint
    }

    /// Regroups fine-grained `NarrationSegment`s into the coarser chunks
    /// `generateWithConfig()` is actually called once per. `.none` and
    /// `.sentence` join into the current group's text exactly like
    /// `[NarrationSegment].plainText` does (straight concatenation / `". "`
    /// join) rather than starting a new synthesis call — a `.sentence` hint
    /// gets no extra spliced silence at all, since the model already pauses
    /// on the period that join inserts (see issue #638's design). Only
    /// `.paragraph`/`.sceneBreak` cut a new group, since only those get an
    /// audible spliced silence in `silenceSeconds(for:)`.
    static func pauseGroups(for segments: [NarrationSegment]) -> [PauseGroup] {
        var groups: [PauseGroup] = []
        for segment in segments {
            switch segment.pauseBefore {
            case .none, .sentence:
                if let last = groups.last {
                    let joiner = segment.pauseBefore == .sentence && !last.text.isEmpty ? ". " : ""
                    groups[groups.count - 1] = PauseGroup(
                        text: last.text + joiner + segment.text, pauseBefore: last.pauseBefore)
                } else {
                    groups.append(PauseGroup(text: segment.text, pauseBefore: .none))
                }
            case .paragraph, .sceneBreak:
                groups.append(PauseGroup(text: segment.text, pauseBefore: segment.pauseBefore))
            }
        }
        return groups
    }

    /// Maps a `PauseGroup`'s leading pause hint to seconds of silence to
    /// splice before it. `.none`/`.sentence` never reach here as a group's
    /// own `pauseBefore` (see `pauseGroups(for:)`), but are handled
    /// explicitly rather than via `default` so a future new `PauseHint`
    /// case fails to compile here instead of silently getting zero pause.
    static func silenceSeconds(for pause: NarrationSegment.PauseHint) -> Double {
        switch pause {
        case .none, .sentence: return 0
        case .paragraph: return paragraphPauseSeconds
        case .sceneBreak: return sceneBreakPauseSeconds
        }
    }

    /// Builds a silent `AVAudioPCMBuffer` of the given duration via the same
    /// `pcmBuffer(from:format:)` transform real synthesized chunks go
    /// through — a `zeros(sampleRate * pauseSeconds)` array, per issue
    /// #638's design. Returns `nil` for zero/negative duration so callers
    /// can skip scheduling anything for `.none`/`.sentence` groups.
    static func silenceBuffer(seconds: Double, format: AVAudioFormat) -> AVAudioPCMBuffer? {
        guard seconds > 0 else { return nil }
        let frameCount = Int(seconds * format.sampleRate)
        guard frameCount > 0 else { return nil }
        return pcmBuffer(from: [Float](repeating: 0, count: frameCount), format: format)
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
