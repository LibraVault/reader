package xyz.libravault.core.billing

import android.app.Activity
import kotlinx.coroutines.flow.Flow

/**
 * Donation/subscription purchasing, flavour-agnostic.
 *
 * The one-time tip stays donation-only, unconditionally ungated, exactly as
 * shipped: a successful purchase just flips `SupporterRepository.setSupporter(true)`
 * so the existing "★ Supporter" badge in Settings lights up.
 *
 * [observeSubscriptionActive] is now also a real feature gate: Premium Cloud
 * TTS Voices (BYOK — docs/cloud-tts-premium-prd.md) is the first LibraVault
 * feature actually conditioned on it (`core:cloudtts`'s `CloudTtsGate`),
 * reversing the standing "no Pro tier" decision (PR #172) — see governance
 * sign-off #449 and issue #400 (this doc previously claimed "nothing in the
 * app is feature-gated by it," which was true when written but is no longer
 * accurate; don't trust an interface's own comment over its real callers).
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
