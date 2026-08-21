package xyz.libravault.core.billing

import android.app.Activity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/**
 * F-Droid build has no billing backend at all — F-Droid can't host one
 * (offline/dependency-free requirement), so this flavour never depends on
 * `billing-ktx` (see core/billing/build.gradle.kts's `playImplementation`
 * scoping). Support on this flavour stays the existing external-link flow in
 * feature:settings (`SUPPORT_URL`) — unchanged by this class.
 */
class NoOpBillingClient @Inject constructor() : SupportBillingClient {

    override val isSupported: Boolean = false

    override fun observeProductsAvailable(): Flow<Boolean> = flowOf(false)

    override fun observeSubscriptionActive(): Flow<Boolean> = flowOf(false)

    override suspend fun purchaseSubscription(activity: Activity): Result<Unit> =
        Result.failure(UnsupportedOperationException("Play Billing is not available on the F-Droid build"))

    override suspend fun purchaseOneTimeTip(activity: Activity): Result<Unit> =
        Result.failure(UnsupportedOperationException("Play Billing is not available on the F-Droid build"))
}
