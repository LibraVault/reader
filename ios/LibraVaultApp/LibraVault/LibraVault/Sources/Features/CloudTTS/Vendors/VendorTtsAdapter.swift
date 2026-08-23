import Foundation

/// One vendor's HTTP surface, dispatched to by `RealCloudTtsProvider`. Mirrors Android's
/// internal `VendorTtsAdapter` interface (core/cloudtts/vendor/VendorTtsAdapter.kt) —
/// narrower than the public `CloudTtsProvider` protocol (no `provider` parameter, since
/// each adapter is already scoped to exactly one vendor); `RealCloudTtsProvider` is the
/// only thing outside `Vendors/` that ever sees this.
///
/// Deliberately NOT guarded by the `isRunningUnderXCTest` check `TTSEngineBridge`/
/// `PocketTTSEngine`/`StoreKitBillingManager` use — that guard exists specifically
/// because *audio hardware activation* (`AVAudioSession`/`AVSpeechSynthesizer`) hangs
/// indefinitely in the CI Simulator (see those types' own doc comments for the
/// confirmed incident). A vendor adapter here only ever makes a plain `URLSession` HTTP
/// call — real in production, intercepted by `MockURLProtocol` in tests — which has no
/// such hardware dependency and doesn't hang. The engine PR's `CloudTtsEngine` is where
/// the *playback* side (an `AVAudioPlayer`, mirroring Android's `MediaPlayerCloudPlayback`)
/// will need that guard, matching the precedent's actual scope rather than applying it
/// here where it would also silently no-op every adapter test against `MockURLProtocol`.
protocol VendorTtsAdapter {
    func synthesize(text: String, voiceID: String, credentials: [CloudCredentialField: String]) async throws -> Data
    func validateKey(credentials: [CloudCredentialField: String]) async throws
}

enum VendorTtsAdapterHelpers {
    /// Shared "execute, fail closed on non-2xx" call, so this isn't hand-duplicated
    /// across all ten synthesize/validateKey call sites — mirrors Kotlin's
    /// `OkHttpClient.executeOrFail`.
    static func executeOrFail(session: URLSession, request: URLRequest, vendorName: String) async throws -> Data {
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            // Task cancellation (e.g. CloudTtsEngine.stop() cancelling an in-flight
            // call) surfaces here as a plain URLError/CancellationError — deliberately
            // rethrown as-is, not wrapped, so a caller further up that specifically
            // checks `is CancellationError`/`URLError.cancelled` (the engine PR's
            // fallback-on-error path, mirroring Kotlin's `runCatchingCancellable` fix)
            // can still tell cancellation apart from a genuine vendor failure.
            throw error
        }
        guard let httpResponse = response as? HTTPURLResponse else {
            throw CloudTtsProviderError.invalidResponse
        }
        guard (200...299).contains(httpResponse.statusCode) else {
            let body = String(data: data, encoding: .utf8) ?? ""
            throw CloudTtsProviderError.httpError(statusCode: httpResponse.statusCode, body: body)
        }
        return data
    }

    /// Google/Azure voice IDs follow vendor-standard `{locale}-{name}` naming (e.g.
    /// Google "en-US-Wavenet-D", Azure "en-US-JennyNeural") — both APIs need the
    /// locale/language tag separately from the voice name. Deliberately not a full
    /// BCP-47 parser — mirrors Kotlin's identical `localeFromVoiceId`.
    static func localeFromVoiceID(_ voiceID: String) -> String {
        let joined = voiceID.split(separator: "-").prefix(2).joined(separator: "-")
        return joined.isEmpty ? "en-US" : joined
    }

    /// JSON-encodes a plain `[String: Any]` request body — used instead of per-request
    /// `Encodable` structs since every vendor's body shape here is small and ad hoc;
    /// mirrors Kotlin's `buildJsonObject` DSL usage for the same reason.
    static func jsonBody(_ dict: [String: Any]) throws -> Data {
        try JSONSerialization.data(withJSONObject: dict)
    }
}

extension Dictionary where Key == CloudCredentialField, Value == String {
    /// Every adapter's `credentials` map is validated by `CloudApiKeyStore.save`
    /// before it's ever persisted, but `validateKey` is explicitly called BEFORE
    /// saving (PRD §6) — so adapters must not assume a field is present. Mirrors
    /// Kotlin's `Map<String, String>.field(name)`.
    func requiredField(_ field: CloudCredentialField) throws -> String {
        guard let value = self[field] else {
            throw CloudTtsProviderError.missingCredentials(field: field)
        }
        return value
    }
}
