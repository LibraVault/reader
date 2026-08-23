import Foundation

/// Real `CloudTtsProvider` — dispatches to whichever of the five vendor adapters
/// `provider` names. Direct port of Android's `RealCloudTtsProvider` (the play-flavor
/// dispatcher there); iOS has no flavor split to mirror (single distribution channel,
/// per the implementation plan), so this is the only `CloudTtsProvider` conformer on
/// this platform — there's no fdroid-equivalent `NoOpCloudTtsProvider` here.
final class RealCloudTtsProvider: CloudTtsProvider {
    private let adapters: [CloudProviderId: VendorTtsAdapter]

    init(session: URLSession = .shared) {
        adapters = [
            .elevenLabs: ElevenLabsAdapter(session: session),
            .openAI: OpenAIAdapter(session: session),
            .googleCloudTTS: GoogleCloudTtsAdapter(session: session),
            .azureSpeech: AzureSpeechAdapter(session: session),
            .amazonPolly: AmazonPollyAdapter(session: session),
        ]
    }

    func synthesize(
        provider: CloudProviderId,
        text: String,
        voiceID: String,
        credentials: [CloudCredentialField: String]
    ) async throws -> Data {
        try await adapter(for: provider).synthesize(text: text, voiceID: voiceID, credentials: credentials)
    }

    func validateKey(provider: CloudProviderId, credentials: [CloudCredentialField: String]) async throws {
        try await adapter(for: provider).validateKey(credentials: credentials)
    }

    /// `adapters` is populated for every `CloudProviderId` case in `init` above, so this
    /// is unreachable in practice — force-unwrap here (not a throwing lookup) makes
    /// that invariant visible rather than silently swallowing a programmer error (a
    /// case added to `CloudProviderId` without a matching adapter entry) as a runtime
    /// `CloudTtsProviderError` a caller might mistake for a real vendor failure.
    private func adapter(for provider: CloudProviderId) -> VendorTtsAdapter {
        adapters[provider]!
    }
}
