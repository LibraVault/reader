package xyz.libravault.core.cloudtts

/**
 * Vendor-agnostic cloud TTS synthesis, dispatched by [CloudProviderId].
 *
 * Real implementation ([xyz.libravault.core.cloudtts.RealCloudTtsProvider],
 * `src/play` — added in the vendor-adapters follow-up) fans out to one of the
 * five vendor HTTP adapters. F-Droid gets [NoOpCloudTtsProvider] (`src/fdroid`)
 * that fails every call, matching [xyz.libravault.core.billing.SupportBillingClient]
 * / `NoOpBillingClient`'s shape — this module never pulls networking
 * dependencies into the F-Droid build.
 *
 * `credentials` is a `Map<String, String>` — see
 * [CloudCredentialFields.requiredFields] for what each [CloudProviderId]
 * expects. Not a single string: four vendors take one API key, but Amazon
 * Polly's real API is AWS SigV4-signed and needs an access-key-ID/
 * secret-access-key pair plus a region, not a bearer token — verified
 * against AWS's actual SynthesizeSpeech API before choosing this shape.
 *
 * Neither method is reachable unless [CloudTtsGate.observeCanUseCloudTts]
 * currently reports true — callers (this module's own `CloudTtsEngine`,
 * added in the engine-wiring follow-up) are responsible for checking the
 * gate immediately before every call, not just once.
 */
interface CloudTtsProvider {

    /** Synthesizes [text] as [voiceId] using [credentials], returning encoded
     * audio bytes on success. Non-streaming, per-paragraph — PRD §3. */
    suspend fun synthesize(
        provider: CloudProviderId,
        text: String,
        voiceId: String,
        credentials: Map<String, String>,
    ): Result<ByteArray>

    /** A cheap call that validates [credentials] are accepted by [provider]
     * without synthesizing real audio — PRD §6, shown as immediate feedback
     * when the user finishes entering credentials in Settings. */
    suspend fun validateKey(provider: CloudProviderId, credentials: Map<String, String>): Result<Unit>
}
