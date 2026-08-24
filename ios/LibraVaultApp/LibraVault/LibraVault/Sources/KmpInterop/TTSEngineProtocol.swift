import Foundation

/// Shared shape for the app's text-to-speech backends - the system voice
/// (`TTSEngineBridge`, wrapping `AVSpeechSynthesizer`) and the on-device
/// neural voice (`PocketTTSEngine`, wrapping sherpa-onnx). Mirrors Android's
/// `TtsEngine` interface (core/tts/TtsEngine.kt) so the two platforms'
/// engine-selection logic stays conceptually aligned, though iOS keeps the
/// selection itself simpler (see `TTSEngineType` in `DomainBridge.swift`) -
/// there's no reactive Settings-driven live-switching flow yet, matching how
/// thin iOS's existing TTS integration already was before this file existed.
protocol TTSEngineProtocol: AnyObject {
    func initialize() async throws
    func speak(text: String, rate: Double) async
    func stop() async
    func pause() async
    func resume() async
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
