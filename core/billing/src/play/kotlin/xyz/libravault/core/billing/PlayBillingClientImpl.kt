package xyz.libravault.core.billing

import android.app.Activity
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import xyz.libravault.core.storage.SupporterRepository
import kotlin.coroutines.resume

/**
 * [SupportBillingClient] backed by Google Play Billing.
 *
 * Store side doesn't exist yet: neither product has been created in Play
 * Console. Every query below treats a non-OK [BillingResult] (including the
 * "unknown product" response Play returns for a product ID it's never heard
 * of) as "not available yet" rather than an error to surface or throw —
 * [observeProductsAvailable] just stays `false` until the products are
 * created and a testing track exists. Real purchase testing is blocked on
 * that store-side setup; this class only wires the code path.
 *
 * [billingClientFactory] takes the [PurchasesUpdatedListener] this class
 * itself is, and returns a connected-but-not-yet-started [BillingClient] —
 * extracted as a constructor parameter (rather than this class calling
 * `BillingClient.newBuilder` directly) so it's unit-testable with a mocked
 * [BillingClient]. See [xyz.libravault.core.billing.BillingModule] for the
 * real factory.
 *
 * [externalScope] is a background scope that outlives any single suspend
 * call — used for the fire-and-forget connect-and-refresh on init and for
 * acknowledging/consuming purchases reported via [onPurchasesUpdated] (a
 * plain callback, not a suspend function). Passed in rather than hardcoded
 * so tests can substitute a synchronous scope (e.g. `Dispatchers.Unconfined`).
 */
class PlayBillingClientImpl(
    private val supporterRepository: SupporterRepository,
    private val externalScope: CoroutineScope,
    billingClientFactory: (PurchasesUpdatedListener) -> BillingClient,
) : SupportBillingClient, PurchasesUpdatedListener {

    override val isSupported: Boolean = true

    private val billingClient: BillingClient = billingClientFactory(this)

    private val _productsAvailable = MutableStateFlow(false)
    private val _subscriptionActive = MutableStateFlow(false)

    @Volatile private var subscriptionDetails: ProductDetails? = null
    @Volatile private var tipDetails: ProductDetails? = null

    // Completed by onPurchasesUpdated once Play reports the outcome of the
    // purchase flow this class itself launched. Never more than one purchase
    // in flight per product at a time (the purchase buttons are disabled
    // while a flow is active — see SettingsScreen), so a single field per
    // product is enough; no need to key by purchase token.
    private var pendingSubscriptionResult: CompletableDeferred<Result<Unit>>? = null
    private var pendingTipResult: CompletableDeferred<Result<Unit>>? = null

    init {
        externalScope.launch { connectAndRefresh() }
    }

    // ── SupportBillingClient ─────────────────────────────────────────────────

    override fun observeProductsAvailable(): Flow<Boolean> = _productsAvailable.asStateFlow()

    override fun observeSubscriptionActive(): Flow<Boolean> = _subscriptionActive.asStateFlow()

    override suspend fun purchaseSubscription(activity: Activity): Result<Unit> =
        launchPurchase(activity, isSubscription = true)

    override suspend fun purchaseOneTimeTip(activity: Activity): Result<Unit> =
        launchPurchase(activity, isSubscription = false)

    // ── PurchasesUpdatedListener ─────────────────────────────────────────────

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            failPending(billingResult.responseCode)
            return
        }
        val purchaseList = purchases.orEmpty()
        if (purchaseList.isEmpty()) {
            failPending(BillingClient.BillingResponseCode.ERROR)
            return
        }
        externalScope.launch {
            purchaseList.forEach { purchase -> handlePurchase(purchase) }
        }
    }

    // ── Purchase flow ─────────────────────────────────────────────────────────

    private suspend fun launchPurchase(activity: Activity, isSubscription: Boolean): Result<Unit> {
        ensureConnected()
        val details = (if (isSubscription) subscriptionDetails else tipDetails)
            ?: return Result.failure(IllegalStateException("Product not available yet"))

        val paramsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
        if (isSubscription) {
            val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken
                ?: return Result.failure(IllegalStateException("No subscription offer available"))
            paramsBuilder.setOfferToken(offerToken)
        }

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(paramsBuilder.build()))
            .build()

        val deferred = CompletableDeferred<Result<Unit>>()
        if (isSubscription) pendingSubscriptionResult = deferred else pendingTipResult = deferred

        val launchResult = billingClient.launchBillingFlow(activity, flowParams)
        if (launchResult.responseCode != BillingClient.BillingResponseCode.OK) {
            if (isSubscription) pendingSubscriptionResult = null else pendingTipResult = null
            return Result.failure(PlayBillingException(launchResult.responseCode))
        }

        // Resolved from onPurchasesUpdated once Play reports the outcome.
        return deferred.await()
    }

    private fun failPending(responseCode: Int) {
        val failure = Result.failure<Unit>(PlayBillingException(responseCode))
        pendingSubscriptionResult?.complete(failure)
        pendingSubscriptionResult = null
        pendingTipResult?.complete(failure)
        pendingTipResult = null
    }

    private suspend fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return

        val isSubscription = purchase.products.contains(SUBSCRIPTION_PRODUCT_ID)
        val isTip = purchase.products.contains(TIP_PRODUCT_ID)
        if (!isSubscription && !isTip) return

        val fulfilled = runCatching {
            when {
                isSubscription && !purchase.isAcknowledged -> acknowledgePurchase(purchase.purchaseToken)
                isSubscription -> true // already acknowledged (e.g. re-delivered on reconnect)
                isTip -> consumePurchase(purchase.purchaseToken)
                else -> false
            }
        }.getOrDefault(false)

        if (!fulfilled) {
            val failure = Result.failure<Unit>(PlayBillingException(BillingClient.BillingResponseCode.ERROR))
            if (isSubscription) pendingSubscriptionResult?.complete(failure)
            if (isTip) pendingTipResult?.complete(failure)
            pendingSubscriptionResult = null
            pendingTipResult = null
            return
        }

        if (isSubscription) _subscriptionActive.value = true
        // Either product completing successfully earns the Supporter badge —
        // subscription active OR a tip received, see SupporterRepository.
        supporterRepository.setSupporter(true)

        if (isSubscription) {
            pendingSubscriptionResult?.complete(Result.success(Unit))
            pendingSubscriptionResult = null
        }
        if (isTip) {
            pendingTipResult?.complete(Result.success(Unit))
            pendingTipResult = null
        }
    }

    // ── Connect + refresh ────────────────────────────────────────────────────

    private suspend fun connectAndRefresh() {
        ensureConnected()
        refreshProductDetails()
        refreshExistingPurchases()
    }

    /**
     * Two separate queries, not one combined list — Play Billing's real
     * `QueryProductDetailsParams.Builder.setProductList` throws
     * `IllegalArgumentException("All products should be of the same product
     * type")` if a single query mixes SUBS and INAPP products, discovered by
     * running this class's tests against the real billing-9.1.0 artifact.
     */
    private suspend fun refreshProductDetails() {
        val subscriptionList = runCatching {
            queryProductDetails(singleProductQuery(SUBSCRIPTION_PRODUCT_ID, BillingClient.ProductType.SUBS))
        }.getOrDefault(emptyList())
        val tipList = runCatching {
            queryProductDetails(singleProductQuery(TIP_PRODUCT_ID, BillingClient.ProductType.INAPP))
        }.getOrDefault(emptyList())

        subscriptionDetails = subscriptionList.firstOrNull { it.productId == SUBSCRIPTION_PRODUCT_ID }
        tipDetails = tipList.firstOrNull { it.productId == TIP_PRODUCT_ID }
        // Both must resolve — if either is missing (store side not set up yet,
        // or any other non-OK response such as "unknown product"), report
        // unavailable rather than allowing a half-configured purchase flow.
        _productsAvailable.value = subscriptionDetails != null && tipDetails != null
    }

    private fun singleProductQuery(productId: String, productType: String): QueryProductDetailsParams =
        QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(productType)
                        .build()
                )
            )
            .build()

    private suspend fun refreshExistingPurchases() {
        val purchases = runCatching { queryPurchases(BillingClient.ProductType.SUBS) }.getOrDefault(emptyList())
        val active = purchases.any {
            it.products.contains(SUBSCRIPTION_PRODUCT_ID) && it.purchaseState == Purchase.PurchaseState.PURCHASED
        }
        _subscriptionActive.value = active
        if (active) supporterRepository.setSupporter(true)
    }

    // ── BillingClient callback → suspend wrappers ───────────────────────────────

    private suspend fun ensureConnected() {
        if (billingClient.isReady) return
        suspendCancellableCoroutine { cont ->
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onBillingServiceDisconnected() {
                    // BillingClient reconnects lazily on the next call — nothing to do here.
                }
            })
        }
    }

    private suspend fun queryProductDetails(params: QueryProductDetailsParams): List<ProductDetails> =
        suspendCancellableCoroutine { cont ->
            billingClient.queryProductDetailsAsync(params) { billingResult, result ->
                if (cont.isActive) {
                    cont.resume(
                        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                            result.productDetailsList
                        } else {
                            emptyList()
                        }
                    )
                }
            }
        }

    private suspend fun queryPurchases(productType: String): List<Purchase> =
        suspendCancellableCoroutine { cont ->
            val params = QueryPurchasesParams.newBuilder().setProductType(productType).build()
            billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
                if (cont.isActive) {
                    cont.resume(
                        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) purchases else emptyList()
                    )
                }
            }
        }

    private suspend fun acknowledgePurchase(purchaseToken: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build()
            billingClient.acknowledgePurchase(params) { result ->
                if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
            }
        }

    private suspend fun consumePurchase(purchaseToken: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val params = ConsumeParams.newBuilder().setPurchaseToken(purchaseToken).build()
            billingClient.consumeAsync(params) { result, _ ->
                if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
            }
        }

    companion object {
        const val SUBSCRIPTION_PRODUCT_ID = "xyz.libravault.subscription.monthly"
        const val TIP_PRODUCT_ID = "xyz.libravault.tip.onetime"
    }
}

/** Billing result translated into a normal exception for [Result.failure]. */
class PlayBillingException(val responseCode: Int) :
    Exception("Play Billing operation failed (responseCode=$responseCode)")
