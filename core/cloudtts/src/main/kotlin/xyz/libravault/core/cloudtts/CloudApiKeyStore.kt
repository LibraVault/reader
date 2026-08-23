package xyz.libravault.core.cloudtts

/**
 * BYOK per-vendor API key storage. Hardware-backed, never plaintext, never
 * logged — PRD §5. Real implementation: [RealCloudApiKeyStore].
 *
 * Deliberately its own small interface (not a direct
 * `EncryptedSharedPreferences` call site) for the same reason
 * [xyz.libravault.core.vaultstore.HardwareKeyWrap] is: this repo has direct
 * prior experience with the alternative going wrong (`core:licensing`'s
 * deleted `ProStateManager` hardcoded Keystore/`EncryptedSharedPreferences`
 * access and was consequently untestable in plain JVM unit tests). This
 * interface is fakeable the same way; see `FakeHardwareKeyWrapFactory` in
 * this module's test source set.
 */
interface CloudApiKeyStore {
    suspend fun saveKey(provider: CloudProviderId, apiKey: String)
    suspend fun loadKey(provider: CloudProviderId): String?
    suspend fun clearKey(provider: CloudProviderId)
}
