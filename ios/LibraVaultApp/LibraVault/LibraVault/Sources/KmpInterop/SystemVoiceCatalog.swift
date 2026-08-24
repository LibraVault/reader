import AVFoundation

/// A real installed system voice, as enumerated via
/// `AVSpeechSynthesisVoice.speechVoices()` — no demo/fallback list. Mirrors Android's
/// `TtsVoiceInfo` (core/tts/TtsState.kt), minus `requiresNetwork`: unlike Android's
/// `TextToSpeech.Voice.isNetworkConnectionRequired`, `AVSpeechSynthesisVoice` exposes
/// no equivalent signal — Apple's system voices are downloaded once via Settings >
/// Accessibility > Spoken Content > Voices, not fetched over the network per
/// utterance, so there is nothing honest to badge here (issue #506).
struct SystemVoiceInfo: Identifiable, Hashable {
    let identifier: String
    let name: String
    /// BCP-47 language tag as reported by `AVSpeechSynthesisVoice.language`, e.g. "en-US".
    let language: String
    let quality: String
    /// `nil` when `AVSpeechSynthesisVoice.gender` is `.unspecified` — most bundled
    /// system voices don't report one, so this is "where available" (issue #506),
    /// not something every voice is expected to carry.
    let gender: String?

    var id: String { identifier }
}

enum SystemVoiceCatalog {
    /// Sorted by language then name, matching `AndroidTtsEngine.buildVoiceList`'s
    /// `compareBy(locale.displayName, name)` ordering — voices for the same language
    /// group together instead of listing in whatever order `speechVoices()` itself
    /// returns them.
    static func availableVoices() -> [SystemVoiceInfo] {
        AVSpeechSynthesisVoice.speechVoices()
            .map { voice in
                SystemVoiceInfo(
                    identifier: voice.identifier,
                    name: voice.name,
                    language: voice.language,
                    quality: displayLabel(for: voice.quality),
                    gender: displayLabel(for: voice.gender)
                )
            }
            .sorted { lhs, rhs in
                lhs.language == rhs.language ? lhs.name < rhs.name : lhs.language < rhs.language
            }
    }

    /// Split out as its own pure function so it's unit-testable without depending on
    /// the real device/Simulator voice catalog, matching this app's established
    /// pattern for anything voice-catalog-adjacent (see `TTSEngineBridge.voice(for:)`'s
    /// own doc comment on why `detectedLanguageCode` is separated out the same way).
    static func displayLabel(for quality: AVSpeechSynthesisVoiceQuality) -> String {
        switch quality {
        case .premium: return "Premium"
        case .enhanced: return "Enhanced"
        default: return "Standard"
        }
    }

    /// `nil` for `.unspecified` — see `SystemVoiceInfo.gender`'s doc comment.
    static func displayLabel(for gender: AVSpeechSynthesisVoiceGender) -> String? {
        switch gender {
        case .male: return "Male"
        case .female: return "Female"
        default: return nil
        }
    }
}
