package xyz.libravault.core.cloudtts

/**
 * BYOK per-vendor credential storage. Hardware-backed, never plaintext,
 * never logged — PRD §5. Real implementation: [RealCloudApiKeyStore].
 *
 * Deliberately its own small interface (not a direct
 * `EncryptedSharedPreferences` call site) for the same reason
 * [xyz.libravault.core.vaultstore.HardwareKeyWrap] is: this repo has direct
 * prior experience with the alternative going wrong (`core:licensing`'s
 * deleted `ProStateManager` hardcoded Keystore/`EncryptedSharedPreferences`
 * access and was consequently untestable in plain JVM unit tests). This
 * interface is fakeable the same way; see `FakeHardwareKeyWrapFactory` in
 * this module's test source set.
 *
 * `credentials` is a `Map<String, String>`, not a single key string:
 * ElevenLabs/OpenAI/Google Cloud TTS use one `"api_key"` field; Azure AI
 * Speech additionally needs a `"region"` field (region-specific host);
 * Amazon Polly needs `"access_key_id"`/`"secret_access_key"`/`"region"` (AWS
 * SigV4-signed, not a bearer key at all — verified against AWS's actual
 * SynthesizeSpeech API, not assumed). See [CloudCredentialFields] for the
 * exact field names each [CloudProviderId] expects.
 */
interface CloudApiKeyStore {
    suspend fun saveCredentials(provider: CloudProviderId, credentials: Map<String, String>)
    suspend fun loadCredentials(provider: CloudProviderId): Map<String, String>?
    suspend fun clearCredentials(provider: CloudProviderId)
}

/**
 * The credential field names each vendor's Settings UI (follow-up PR) must
 * collect, and each vendor adapter (vendor-adapters follow-up PR) can rely
 * on being present in a validated [CloudApiKeyStore] entry. Single source of
 * truth so the UI's per-provider form and the adapter's expectations can't
 * silently drift apart.
 */
object CloudCredentialFields {
    const val API_KEY = "api_key"
    const val REGION = "region"
    const val ACCESS_KEY_ID = "access_key_id"
    const val SECRET_ACCESS_KEY = "secret_access_key"

    fun requiredFields(provider: CloudProviderId): Set<String> = when (provider) {
        CloudProviderId.ELEVENLABS, CloudProviderId.OPENAI, CloudProviderId.GOOGLE_CLOUD_TTS -> setOf(API_KEY)
        CloudProviderId.AZURE_SPEECH -> setOf(API_KEY, REGION)
        CloudProviderId.AMAZON_POLLY -> setOf(ACCESS_KEY_ID, SECRET_ACCESS_KEY, REGION)
    }
}
