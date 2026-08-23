package xyz.libravault.core.cloudtts

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import xyz.libravault.core.vaultstore.HardwareKeyWrap
import xyz.libravault.core.vaultstore.HardwareKeyWrapFactory
import xyz.libravault.core.vaultstore.KeystoreKeyLostException
import xyz.libravault.core.vaultstore.WrappedBlob
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFERENCES_NAME = "cloud_tts_api_keys"

/** One shared hardware-backed key wraps every vendor's key — simpler than a
 * Keystore alias per vendor, and this store's threat model (a single BYOK
 * secret per vendor, not a vault master key) doesn't need per-vendor
 * key rotation independence. */
private const val KEY_WRAP_ALIAS = "cloud_tts_api_keys"

val Context.cloudApiKeyDataStore: DataStore<Preferences> by preferencesDataStore(name = PREFERENCES_NAME)

/**
 * Real, Android Keystore-backed [CloudApiKeyStore]. Wraps each plaintext key
 * via [HardwareKeyWrapFactory] (reusing `core:vaultstore`'s existing,
 * already-hardened wrap/unwrap seam rather than re-deriving Keystore
 * alias/StrongBox/TEE logic a second time) before persisting the resulting
 * nonce+ciphertext pair to a plain (non-"Encrypted") DataStore file — the
 * DataStore itself never sees plaintext.
 *
 * Primary constructor is `internal` and takes the [DataStore] directly so
 * JVM tests can construct this against an in-memory/temp-file DataStore and
 * a fake [HardwareKeyWrapFactory] with no Android Context or Robolectric —
 * the [Inject]-annotated secondary constructor is what Hilt actually uses.
 *
 * NOTE: [HardwareKeyWrapFactory.createNew] throws
 * [xyz.libravault.core.vaultstore.KeystoreHardwareUnavailableException] on a
 * device with no hardware-backed Keystore at all (no StrongBox, no TEE) —
 * deliberately not caught here, per `HardwareKeyWrap`'s own "do not silently
 * downgrade" contract. Settings UI (Cloud Voices key-entry screen) must
 * catch this and show "Cloud Voices isn't available on this device" rather
 * than crash or silently fall back to a software-backed key.
 */
@Singleton
class RealCloudApiKeyStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val hardwareKeyWrapFactory: HardwareKeyWrapFactory,
) : CloudApiKeyStore {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        hardwareKeyWrapFactory: HardwareKeyWrapFactory,
    ) : this(context.cloudApiKeyDataStore, hardwareKeyWrapFactory)

    // Lazily created/loaded once, then reused for every provider's wrap/unwrap
    // call — see KEY_WRAP_ALIAS.
    private val keyWrap: HardwareKeyWrap by lazy {
        try {
            hardwareKeyWrapFactory.forExisting(KEY_WRAP_ALIAS)
        } catch (e: KeystoreKeyLostException) {
            hardwareKeyWrapFactory.createNew(KEY_WRAP_ALIAS)
        }
    }

    override suspend fun saveKey(provider: CloudProviderId, apiKey: String) {
        val wrapped = keyWrap.wrap(apiKey.toByteArray(Charsets.UTF_8))
        dataStore.edit { prefs ->
            prefs[nonceKey(provider)] = encode(wrapped.nonce)
            prefs[ciphertextKey(provider)] = encode(wrapped.ciphertext)
        }
    }

    override suspend fun loadKey(provider: CloudProviderId): String? {
        val prefs = dataStore.data.first()
        val nonce = prefs[nonceKey(provider)] ?: return null
        val ciphertext = prefs[ciphertextKey(provider)] ?: return null
        val wrapped = WrappedBlob(nonce = decode(nonce), ciphertext = decode(ciphertext))
        return keyWrap.unwrap(wrapped).toString(Charsets.UTF_8)
    }

    override suspend fun clearKey(provider: CloudProviderId) {
        dataStore.edit { prefs ->
            prefs.remove(nonceKey(provider))
            prefs.remove(ciphertextKey(provider))
        }
    }

    private fun nonceKey(provider: CloudProviderId) = stringPreferencesKey("${provider.name}_nonce")
    private fun ciphertextKey(provider: CloudProviderId) = stringPreferencesKey("${provider.name}_ciphertext")

    // java.util.Base64, not android.util.Base64: pure JVM, so this class
    // stays testable in plain (non-Robolectric) unit tests. Safe at this
    // project's minSdk 31 (java.util.Base64 needs API 26+).
    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun decode(string: String): ByteArray = Base64.getDecoder().decode(string)
}
