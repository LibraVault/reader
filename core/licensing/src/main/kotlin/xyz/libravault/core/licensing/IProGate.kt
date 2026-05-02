package xyz.libravault.core.licensing

import kotlinx.coroutines.flow.StateFlow

/**
 * Common interface for Pro unlock mechanisms.
 *
 * Implementations:
 *   play flavour  → PlayBillingProGate  (Google Play one-tap purchase, v2)
 *   fdroid/direct → KeyProGate          (Ed25519 license key)
 *
 * The rest of the app — feature gates, locked UI, settings — depends only
 * on this interface and never imports a flavour-specific class.
 */
interface IProGate {
    /** Emits true when the user holds a valid Pro entitlement. */
    val isPro: StateFlow<Boolean>

    /**
     * Attempt to activate Pro with a license key string.
     * Returns true if the key verified and was persisted.
     */
    fun activateWithKey(licenseKey: String): Boolean

    /**
     * Called on app startup to refresh entitlement state.
     * KeyProGate: re-verifies stored key signature — entirely offline.
     * PlayBillingProGate (v2): queries Google Play.
     */
    suspend fun refresh()

    /** Whether this gate accepts in-app key entry (false for play billing). */
    val supportsKeyEntry: Boolean
}
