package xyz.libravault.core.cloudtts

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.libravault.core.vaultcrypto.VaultAuthenticationException
import xyz.libravault.core.vaultstore.HardwareKeyWrap
import xyz.libravault.core.vaultstore.HardwareKeyWrapFactory
import xyz.libravault.core.vaultstore.KeystoreKeyLostException
import xyz.libravault.core.vaultstore.WrappedBlob
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFERENCES_NAME = "cloud_tts_api_keys"

/** One shared hardware-backed key wraps every vendor's credentials — simpler
 * than a Keystore alias per vendor, and this store's threat model (a single
 * BYOK secret set per vendor, not a vault master key) doesn't need
 * per-vendor key rotation independence. */
private const val KEY_WRAP_ALIAS = "cloud_tts_api_keys"

val Context.cloudApiKeyDataStore: DataStore<Preferences> by preferencesDataStore(name = PREFERENCES_NAME)

/**
 * Real, Android Keystore-backed [CloudApiKeyStore]. Each provider's
 * `Map<String, String>` credentials are JSON-serialized, then wrapped as one
 * blob via [HardwareKeyWrapFactory] (reusing `core:vaultstore`'s existing,
 * already-hardened wrap/unwrap seam rather than re-deriving Keystore
 * alias/StrongBox/TEE logic a second time) before persisting the resulting
 * nonce+ciphertext pair to a plain (non-"Encrypted") DataStore file — the
 * DataStore itself never sees plaintext, and the JSON never leaves this
 * class unwrapped.
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
 *
 * Read/write deliberately use different get-or-create semantics for the
 * shared wrap key (this used to be one cached `by lazy` property — a real
 * bug caught in review, see the two private helpers below for why it was
 * split): a *write* may mint a fresh Keystore key on first use, but a *read*
 * must never silently mint one — doing so on a [KeystoreKeyLostException]
 * would leave every other provider's still-on-disk ciphertext permanently
 * unwrappable under the new key with no caller-visible signal.
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

    /** For writes: get the existing wrap key, or mint one if this is the very
     * first credential ever saved. Safe to create here — a write is about to
     * make this provider's entry consistent with whatever key comes back. */
    private fun getOrCreateKeyWrapForWrite(): HardwareKeyWrap =
        try {
            hardwareKeyWrapFactory.forExisting(KEY_WRAP_ALIAS)
        } catch (e: KeystoreKeyLostException) {
            hardwareKeyWrapFactory.createNew(KEY_WRAP_ALIAS)
        }

    /** For reads: only ever use an existing key. `null` means the key is
     * gone (or never existed) — every provider's ciphertext under this alias
     * is unreadable, which is exactly equivalent to "nothing saved" from a
     * caller's point of view, and self-heals the next time the user saves a
     * fresh credential (a new key gets minted then, by [getOrCreateKeyWrapForWrite]). */
    private fun existingKeyWrapForRead(): HardwareKeyWrap? =
        try {
            hardwareKeyWrapFactory.forExisting(KEY_WRAP_ALIAS)
        } catch (e: KeystoreKeyLostException) {
            null
        }

    override suspend fun saveCredentials(provider: CloudProviderId, credentials: Map<String, String>) {
        require(credentials.keys == CloudCredentialFields.requiredFields(provider)) {
            "Cloud TTS credentials for $provider must have exactly the fields " +
                "${CloudCredentialFields.requiredFields(provider)}, got ${credentials.keys} — " +
                "failing fast here rather than at synthesis time."
        }
        withContext(Dispatchers.IO) {
            val json = Json.encodeToString(credentials)
            val wrapped = getOrCreateKeyWrapForWrite().wrap(json.toByteArray(Charsets.UTF_8))
            dataStore.edit { prefs ->
                prefs[nonceKey(provider)] = encode(wrapped.nonce)
                prefs[ciphertextKey(provider)] = encode(wrapped.ciphertext)
            }
        }
    }

    override suspend fun loadCredentials(provider: CloudProviderId): Map<String, String>? = withContext(Dispatchers.IO) {
        val prefs = dataStore.data.first()
        val nonce = prefs[nonceKey(provider)] ?: return@withContext null
        val ciphertext = prefs[ciphertextKey(provider)] ?: return@withContext null
        val keyWrap = existingKeyWrapForRead() ?: return@withContext null
        val wrapped = WrappedBlob(nonce = decode(nonce), ciphertext = decode(ciphertext))
        try {
            val json = keyWrap.unwrap(wrapped).toString(Charsets.UTF_8)
            Json.decodeFromString<Map<String, String>>(json)
        } catch (e: VaultAuthenticationException) {
            // Ciphertext exists but doesn't verify under the current key — the
            // shared wrap key was replaced (lost-key recovery, see class doc)
            // after this entry was written. Treat as absent rather than
            // crashing the caller; the stale entry is orphaned but harmless.
            null
        }
    }

    override suspend fun clearCredentials(provider: CloudProviderId) {
        withContext(Dispatchers.IO) {
            dataStore.edit { prefs ->
                prefs.remove(nonceKey(provider))
                prefs.remove(ciphertextKey(provider))
            }
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
