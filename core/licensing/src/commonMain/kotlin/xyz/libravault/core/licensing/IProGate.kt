package xyz.libravault.core.licensing

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Common interface for Pro unlock mechanisms.
 *
 * Platform-specific implementations:
 *   Android/play  → PlayBillingProGate  (Google Play one-tap purchase)
 *   Android/fdroid→ KeyProGate          (Ed25519 license key)
 *   iOS           → TBD (Phase B)       (App Store IAP or key-based)
 *
 * NOTE: Activity-dependent purchase flow moved to androidMain extension.
 * iOS will have its own UI flow via SwiftUI.
 */
interface IProGate {
    /** Emits true when the user holds a valid Pro entitlement. */
    val isPro: StateFlow<Boolean>

    /**
     * Attempt to activate Pro with a license key string.
     * Returns true if the key verified and was persisted.
     *
     * iOS: implement with local file + app-level encryption
     * Android: Ed25519 signature verification
     */
    fun activateWithKey(licenseKey: String): Boolean

    /**
     * Called on app startup to refresh entitlement state.
     * Android/fdroid: re-verifies stored key signature (entirely offline)
     * Android/play: queries Google Play
     * iOS: TBD (App Store ServerAPI or local cache validation)
     */
    suspend fun refresh()

    /** Whether this gate accepts in-app key entry. */
    val supportsKeyEntry: Boolean

    /**
     * Emits non-success outcomes from platform-specific purchase flows.
     * Android/play: [PurchaseOutcome.UserCancelled], [PurchaseOutcome.Error]
     * Android/fdroid: never emits
     * iOS: TBD
     */
    val purchaseOutcomes: SharedFlow<PurchaseOutcome>
}
