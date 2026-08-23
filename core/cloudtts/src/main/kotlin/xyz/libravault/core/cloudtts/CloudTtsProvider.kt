package xyz.libravault.core.cloudtts

/**
 * Vendor-agnostic cloud TTS synthesis, dispatched by [CloudProviderId].
 *
 * Real implementation ([xyz.libravault.core.cloudtts.RealCloudTtsProvider],
 * `src/play` — added in the vendor-adapters follow-up) fans out to one of the
 * five vendor HTTP adapters. F-Droid gets a `NoOpCloudTtsProvider` (`src/fdroid`)
 * that fails every call, matching [xyz.libravault.core.billing.SupportBillingClient]
 * / `NoOpBillingClient`'s shape — this module never pulls networking
 * dependencies into the F-Droid build.
 *
 * Neither method is reachable unless [CloudTtsGate.observeCanUseCloudTts]
 * currently reports true — callers (this module's own `CloudTtsEngine`,
 * added in the engine-wiring follow-up) are responsible for checking the
 * gate immediately before every call, not just once.
 */
interface CloudTtsProvider {

    /** Synthesizes [text] as [voiceId] using [apiKey], returning encoded audio
     * bytes on success. Non-streaming, per-paragraph — PRD §3. */
    suspend fun synthesize(provider: CloudProviderId, text: String, voiceId: String, apiKey: String): Result<ByteArray>

    /** A cheap call that validates [apiKey] is accepted by [provider] without
     * synthesizing real audio — PRD §6, shown as immediate feedback when the
     * user pastes a key into Settings. */
    suspend fun validateKey(provider: CloudProviderId, apiKey: String): Result<Unit>
}
