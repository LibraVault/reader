import Foundation

/// Shared shape for the app's text-to-speech backends - the system voice
/// (`TTSEngineBridge`, wrapping `AVSpeechSynthesizer`), the on-device neural
/// voice (`PocketTTSEngine`, wrapping sherpa-onnx), and cloud voices
/// (`CloudTtsEngine`). Mirrors Android's `TtsEngine` interface
/// (core/tts/TtsEngine.kt) so the two platforms' engine-selection logic stays
/// conceptually aligned (see `TTSEngineType` below).
protocol TTSEngineProtocol: AnyObject {
    func initialize() async throws
    func speak(text: String, rate: Double) async
    func stop() async
    func pause() async
    func resume() async

    /// Overrides automatic per-utterance voice selection with a specific
    /// installed voice, or clears the override (`nil`) to go back to
    /// automatic. Mirrors Android's `TtsEngine.setVoice(voiceId)` - a
    /// separate call from `speak`, not a per-call parameter, so engines that
    /// have no concept of "which voice" (Pocket TTS ships exactly one; Cloud
    /// picks a voice per its own provider config) can just ignore it via the
    /// default no-op below instead of every conformer needing real logic.
    /// Only `TTSEngineBridge` (System Voice) does anything with this today -
    /// see #506.
    func setVoice(identifier: String?) async

    /// Segment-aware narration (#499 v2a Phase A) — carries prosody hints
    /// (pause/emphasis) derived from source document structure, instead of
    /// the one flat `String` `speak(text:rate:)` gets. `TTSEngineBridge`
    /// (System Voice) renders segments to SSML; `PocketTTSEngine` (#638)
    /// splices silence between separately-synthesized chunks for
    /// `.paragraph`/`.sceneBreak` pause hints — it has no lever at all for
    /// `.emphasis`/`.quote` (a permanent capability gap in its bound API),
    /// so those segments' text is narrated exactly as plain text. Cloud
    /// isn't in scope for this phase, so it falls through to the default
    /// extension below, which just narrates the segments' flattened plain
    /// text exactly as `speak(text:rate:)` already would have — never a
    /// regression, just no new capability yet.
    func speak(segments: [NarrationSegment], rate: Double) async
}

extension TTSEngineProtocol {
    func setVoice(identifier: String?) async {}

    func speak(segments: [NarrationSegment], rate: Double) async {
        await speak(text: segments.plainText, rate: rate)
    }
}

/// Mirrors Android's `TtsEngineType` (core/tts/TtsEngineFactory.kt). `.cloud` added for
/// Premium Cloud TTS Voices (BYOK) — see `CloudTtsEngine`/`LibravaultDomainBridge
/// .switchTTSEngine(to:)`.
enum TTSEngineType: String, CaseIterable {
    case system
    case pocket
    case cloud

    var displayName: String {
        switch self {
        case .system: return "System Voice"
        case .pocket: return "On-Device (Pocket TTS)"
        case .cloud: return "Cloud Voices"
        }
    }
}
