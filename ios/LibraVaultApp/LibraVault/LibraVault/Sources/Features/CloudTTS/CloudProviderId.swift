import Foundation

/// Mirrors Android's `CloudProviderId` (core/cloudtts/CloudProviderId.kt) — the PRD's
/// closed list of five vendor presets (docs/cloud-tts-premium-prd.md §3). `String`-backed
/// and `Codable` so it round-trips directly through `UserDefaults`/Keychain storage
/// (see `CloudVoicePreferences`/`KeychainCloudApiKeyStore`) without a separate raw-value
/// mapping layer. Raw values are camelCase (Swift convention), NOT the same strings as
/// Android's `CloudProviderId.name` (SCREAMING_SNAKE_CASE, e.g. `"GOOGLE_CLOUD_TTS"`) —
/// the two platforms never share persisted state, so there's no cross-platform format to
/// keep in sync here, unlike `StoreKitBillingManager`'s product ids.
enum CloudProviderId: String, CaseIterable, Codable {
    case elevenLabs
    case openAI
    case googleCloudTTS
    case azureSpeech
    case amazonPolly

    var displayName: String {
        switch self {
        case .elevenLabs: return "ElevenLabs"
        case .openAI: return "OpenAI"
        case .googleCloudTTS: return "Google Cloud TTS"
        case .azureSpeech: return "Azure AI Speech"
        case .amazonPolly: return "Amazon Polly"
        }
    }
}
