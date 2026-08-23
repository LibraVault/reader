package xyz.libravault.core.cloudtts.vendor

/**
 * The fixed, closed set of hosts the five vendor adapters may ever call —
 * single source of truth for `CloudTtsFixedHostsRegressionTest`. PRD §7:
 * pinning these means a future change can't silently turn one into a
 * user-configurable value, or add a sixth vendor, without that being a
 * deliberate, reviewed diff — no custom/arbitrary endpoint in v1 (PRD §3).
 *
 * Azure and Amazon Polly are region-specific (the region comes from the
 * user's own credentials, PRD BYOK) — for those two, only the fixed
 * host *pattern* is pinned, not a literal, matching the PRD's explicit
 * callout for Azure. Azure additionally has two real hosts: the
 * synthesis endpoint and a separate token-issuance endpoint used for the
 * (non-synthesizing) key-validation call.
 */
internal object CloudTtsFixedHosts {
    const val ELEVENLABS = "api.elevenlabs.io"
    const val OPENAI = "api.openai.com"
    const val GOOGLE_CLOUD_TTS = "texttospeech.googleapis.com"

    const val AZURE_SPEECH_HOST_SUFFIX = ".tts.speech.microsoft.com"
    const val AZURE_TOKEN_HOST_SUFFIX = ".api.cognitive.microsoft.com"

    const val AMAZON_POLLY_HOST_PREFIX = "polly."
    const val AMAZON_POLLY_HOST_SUFFIX = ".amazonaws.com"

    fun azureSpeechHost(region: String) = "$region$AZURE_SPEECH_HOST_SUFFIX"
    fun azureTokenHost(region: String) = "$region$AZURE_TOKEN_HOST_SUFFIX"
    fun pollyHost(region: String) = "$AMAZON_POLLY_HOST_PREFIX$region$AMAZON_POLLY_HOST_SUFFIX"
}
