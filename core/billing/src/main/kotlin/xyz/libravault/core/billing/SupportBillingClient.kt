package xyz.libravault.core.billing

import android.app.Activity
import kotlinx.coroutines.flow.Flow

/**
 * Donation/subscription purchasing, flavour-agnostic.
 *
 * This is donation-only — nothing in the app is feature-gated by it, matching
 * the standing "no Pro tier" product decision (see core:licensing's deletion,
 * PR #172). A successful purchase of either product just flips
 * `SupporterRepository.setSupporter(true)` so the existing "★ Supporter"
 * badge in Settings lights up.
 *
 * Platform-specific implementations:
 *   Android/play   → [xyz.libravault.core.billing.PlayBillingClientImpl] (Google Play Billing)
 *   Android/fdroid → [xyz.libravault.core.billing.NoOpBillingClient]     (no billing backend, offline)
 */
interface SupportBillingClient {

    /** False on F-Droid (no billing backend exists there at all). */
    val isSupported: Boolean

    /**
     * Emits whether both products (`xyz.libravault.subscription.monthly` and
     * `xyz.libravault.tip.onetime`) are currently purchasable. False whenever
     * they haven't been created in Play Console yet, or the Play Billing
     * connection otherwise can't resolve them — this is the expected state
     * until the store side of setup is complete, never a crash.
     */
    fun observeProductsAvailable(): Flow<Boolean>

    /** Emits whether the user currently holds an active monthly subscription. */
    fun observeSubscriptionActive(): Flow<Boolean>

    /** Launches the purchase flow for the monthly subscription from [activity]. */
    suspend fun purchaseSubscription(activity: Activity): Result<Unit>

    /** Launches the purchase flow for the one-time consumable tip from [activity]. */
    suspend fun purchaseOneTimeTip(activity: Activity): Result<Unit>
}
