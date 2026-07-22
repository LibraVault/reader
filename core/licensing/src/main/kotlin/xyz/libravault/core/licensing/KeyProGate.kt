package xyz.libravault.core.licensing

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * IProGate implementation for the F-Droid / direct-download build.
 *
 * Wraps ProStateManager. All verification is offline via Ed25519.
 * Recovery mechanism (BIP39 / PIN / email) is deferred to v2.
 *
 * v2 recovery options:
 *   A) Short PIN (6–8 digits) + server-side rate limiting
 *   B) User-initiated "email key to myself" (key sent then discarded)
 *   C) No recovery; re-issue on support request with proof of purchase
 * Recommendation: start with C — zero infrastructure, fits FOSS ethos.
 */
class KeyProGate(context: Context) : IProGate {

    private val stateManager = ProStateManager(context)

    override val isPro: StateFlow<Boolean> = stateManager.isPro
    override val supportsKeyEntry: Boolean = true

    override fun activateWithKey(licenseKey: String): Boolean =
        stateManager.activate(licenseKey)

    /** Re-verifies stored key signature — entirely offline. */
    override suspend fun refresh() {
        // ProStateManager already re-verifies on construction.
        // Nothing additional needed unless adding expiry logic in v2.
    }
}
