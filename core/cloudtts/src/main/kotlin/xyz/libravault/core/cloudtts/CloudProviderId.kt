package xyz.libravault.core.cloudtts

/**
 * The five BYOK vendor presets PRD §3 scopes for v1 — fixed and closed, not a
 * user-extensible list (no custom/arbitrary provider endpoint in v1, PRD §3
 * "Explicitly deferred"). Adding a sixth entry here is a deliberate, reviewed
 * product decision, not a config change — see
 * CloudTtsFixedHostsRegressionTest, which pins this list's hosts.
 */
enum class CloudProviderId {
    ELEVENLABS,
    OPENAI,
    GOOGLE_CLOUD_TTS,
    AZURE_SPEECH,
    AMAZON_POLLY,
}
