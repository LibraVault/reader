import Foundation

/// One HTTP call shape shared by all five vendor adapters (`Vendors/*Adapter.swift`) —
/// iOS counterpart to Android's `CloudTtsProvider` interface (core/cloudtts/CloudTtsProvider.kt).
/// A protocol, not a direct `URLSession` call at every call site, both for fakeability in
/// tests and so `CloudTtsEngine` can dispatch to whichever vendor is actually selected
/// without a hardcoded `switch` of its own — `RealCloudTtsProvider` (added in the
/// vendors PR) is the dispatcher; this is just the shared contract.
///
/// Every conformer/caller MUST check `CloudTtsGate.canUseCloudTts` immediately before
/// calling either method below — this protocol has no way to enforce that itself, since
/// enforcing it here would require this protocol to depend on `StoreKitBillingManager`/
/// `CloudVoicePreferences`, which would invert the dependency direction this feature is
/// built on (see the implementation plan's "Engine integration seam"). `CloudTtsEngine`
/// (added in the engine PR) is the actual enforcement point.
protocol CloudTtsProvider {
    /// Synthesizes `text` as speech using `voiceID` and `credentials`, returning the raw
    /// audio bytes. Format is vendor-specific (MP3 for most) — handled generically by
    /// `AVAudioPlayer` on the `CloudTtsEngine` side, the same "don't care about the
    /// container format, just play the bytes" approach Android's `MediaPlayer`-based
    /// `CloudPlayback` takes.
    func synthesize(
        provider: CloudProviderId,
        text: String,
        voiceID: String,
        credentials: [CloudCredentialField: String]
    ) async throws -> Data

    /// A single cheap validation call (PRD §6: "validated with a single cheap test
    /// call, then stored") — never persists anything itself. The caller only saves
    /// credentials (via `CloudApiKeyStore`) once this succeeds.
    func validateKey(
        provider: CloudProviderId,
        credentials: [CloudCredentialField: String]
    ) async throws
}

/// Errors this protocol's conformers throw — deliberately generic (not
/// per-vendor-specific HTTP status parsing) since callers only need to know "did it
/// work," not why, to decide whether to fall back to the on-device engine. Mirrors
/// Android's `CloudTtsProvider`'s `Result<...>` failure shape, adapted to Swift's
/// `throws` convention.
enum CloudTtsProviderError: LocalizedError, Equatable {
    case missingCredentials(field: CloudCredentialField)
    case httpError(statusCode: Int, body: String)
    case invalidResponse

    var errorDescription: String? {
        switch self {
        case .missingCredentials(let field):
            return "Missing required field: \(field.label)"
        case .httpError(let statusCode, let body):
            return "HTTP \(statusCode): \(body.prefix(200))"
        case .invalidResponse:
            return "Unexpected response from the TTS vendor"
        }
    }
}
