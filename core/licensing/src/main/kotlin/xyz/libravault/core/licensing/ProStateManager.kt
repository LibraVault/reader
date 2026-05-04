package xyz.libravault.core.licensing

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Persists the Pro unlock flag in EncryptedSharedPreferences.
 *
 * We store the license key itself (not just a boolean flag) so we can
 * re-verify the Ed25519 signature on every launch. Flipping a bare
 * boolean on a rooted device is not enough to spoof activation.
 */
class ProStateManager(context: Context) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _isPro = MutableStateFlow(loadAndVerify())
    val isPro: StateFlow<Boolean> = _isPro

    /** Persists [licenseKey] if it verifies. Returns true on success. */
    fun activate(licenseKey: String): Boolean {
        val result = LicenseVerifier.verify(licenseKey)
        if (result !is LicenseVerifier.Result.Valid) return false
        prefs.edit()
            .putString(KEY_LICENSE, licenseKey.trim())
            .putString(KEY_TOKEN_ID, result.tokenId)
            .apply()
        _isPro.value = true
        return true
    }

    fun deactivate() {
        prefs.edit().clear().apply()
        _isPro.value = false
    }

    fun storedLicenseKey(): String? = prefs.getString(KEY_LICENSE, null)

    private fun loadAndVerify(): Boolean {
        val key = prefs.getString(KEY_LICENSE, null) ?: return false
        return LicenseVerifier.verify(key) is LicenseVerifier.Result.Valid
    }

    companion object {
        private const val PREFS_NAME  = "libravault_pro"
        private const val KEY_LICENSE = "license_key"
        private const val KEY_TOKEN_ID = "token_id"
    }
}
