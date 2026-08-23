import Foundation

/// Mirrors Android's `CloudCredentialFields` object (core/cloudtts/CloudApiKeyStore.kt)
/// field-by-field — kept in sync by hand, like `StoreKitBillingManager`'s product ids,
/// since Kotlin and Swift can't share a constant here. A single `apiKey: String` couldn't
/// express Amazon Polly's real (SigV4, multi-field) auth — same reasoning as Android's
/// identical `Map<String, String>`-shaped credentials.
enum CloudCredentialField: String, Codable, CaseIterable, Hashable {
    case apiKey
    case region
    case accessKeyID
    case secretAccessKey

    var label: String {
        switch self {
        case .apiKey: return "API Key"
        case .region: return "Region"
        case .accessKeyID: return "Access Key ID"
        case .secretAccessKey: return "Secret Access Key"
        }
    }
}

enum CloudCredentialFields {
    /// Which fields each vendor's real auth scheme actually needs. Mirrors Android's
    /// `CloudCredentialFields.requiredFields` exactly:
    ///  - ElevenLabs (`xi-api-key` header), OpenAI, Google Cloud TTS (bearer header) —
    ///    a single API key.
    ///  - Azure AI Speech — API key plus a region (its host is region-scoped:
    ///    `{region}.tts.speech.microsoft.com`).
    ///  - Amazon Polly — SigV4 request signing needs an access key id, secret access
    ///    key, AND region; there's no single bearer token.
    static func requiredFields(for provider: CloudProviderId) -> Set<CloudCredentialField> {
        switch provider {
        case .elevenLabs, .openAI, .googleCloudTTS:
            return [.apiKey]
        case .azureSpeech:
            return [.apiKey, .region]
        case .amazonPolly:
            return [.accessKeyID, .secretAccessKey, .region]
        }
    }
}
