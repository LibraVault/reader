package xyz.libravault.core.licensing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * IProGate backed by Google Play Billing (one-time in-app product).
 *
 * Lifecycle:
 *   1. App start → LicensingModule provides this; MainActivity calls refresh().
 *      Queries Play for existing purchases — works offline after first purchase.
 *   2. User taps "Get Pro" → launchPurchaseFlow(activity).
 *      Google handles payment sheet, receipt, fraud checks.
 *   3. onPurchasesUpdated → isPro emits true, purchase acknowledged.
 *
 * Recovery: Google restores purchases automatically when the user signs in
 * with the same account on a new device. No server or key needed.
 *
 * Setup: create a one-time in-app product in Play Console with
 * product ID "libravault_pro" before releasing.
 */
class PlayBillingProGate(context: Context) : IProGate, PurchasesUpdatedListener {

    private val _isPro = MutableStateFlow(false)
    override val isPro: StateFlow<Boolean> = _isPro
    override val supportsKeyEntry: Boolean = false

    private val _purchaseOutcomes = MutableSharedFlow<PurchaseOutcome>(extraBufferCapacity = 1)
    override val purchaseOutcomes: SharedFlow<PurchaseOutcome> = _purchaseOutcomes

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .build()

    private var proProductDetails: ProductDetails? = null

    // ── IProGate ─────────────────────────────────────────────────────────────

    /** Not used in the play flavour — purchasing goes through launchPurchaseFlow(). */
    override fun activateWithKey(licenseKey: String): Boolean = false

    /**
     * Connects to Play (if needed) and checks for an existing Pro purchase.
     * Safe to call on every app start — BillingClient caches results locally.
     */
    override suspend fun refresh() {
        ensureConnected()
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val result = billingClient.queryPurchasesAsync(params)
        _isPro.value = result.purchasesList.any { isProPurchase(it) }
        // Pre-fetch product details so the purchase sheet opens instantly
        fetchProductDetails()
    }

    // ── Play-specific ─────────────────────────────────────────────────────────

    /**
     * Opens the Play purchase sheet. Call from an Activity context only.
     * The result arrives asynchronously via onPurchasesUpdated / purchaseEvents.
     */
    suspend fun launchPurchaseFlow(activity: Activity) {
        ensureConnected()
        val details = proProductDetails ?: run { fetchProductDetails(); proProductDetails } ?: return
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            )
            .build()
        billingClient.launchBillingFlow(activity, flowParams)
        // Result arrives in onPurchasesUpdated
    }

    // ── PurchasesUpdatedListener ──────────────────────────────────────────────

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            val hasPro = purchases?.any { isProPurchase(it) } == true
            if (hasPro) {
                _isPro.value = true
                purchases?.forEach { purchase ->
                    if (!purchase.isAcknowledged) {
                        billingClient.acknowledgePurchase(
                            AcknowledgePurchaseParams.newBuilder()
                                .setPurchaseToken(purchase.purchaseToken)
                                .build()
                        ) { /* fire and forget — Google retries on failure */ }
                    }
                }
            }
        } else {
            val outcome = if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED)
                PurchaseOutcome.UserCancelled
            else
                PurchaseOutcome.Error
            _purchaseOutcomes.tryEmit(outcome)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun ensureConnected() {
        if (billingClient.isReady) return
        suspendCancellableCoroutine { cont ->
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) { cont.resume(Unit) }
                override fun onBillingServiceDisconnected() { /* BillingClient reconnects */ }
            })
        }
    }

    private suspend fun fetchProductDetails() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        val result = billingClient.queryProductDetails(params)
        proProductDetails = result.productDetailsList?.firstOrNull()
    }

    companion object {
        /** Must match the product ID in Play Console → Monetise → In-app products. */
        const val PRODUCT_ID = "libravault_pro"

        internal fun isProPurchase(purchase: Purchase) =
            purchase.products.contains(PRODUCT_ID) && purchase.purchaseState == Purchase.PurchaseState.PURCHASED
    }
}
