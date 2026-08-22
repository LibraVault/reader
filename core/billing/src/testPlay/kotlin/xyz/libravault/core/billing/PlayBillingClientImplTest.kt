package xyz.libravault.core.billing

import com.android.billingclient.api.AcknowledgePurchaseResponseListener
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeResponseListener
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.QueryProductDetailsResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.libravault.core.storage.SupporterRepository

/**
 * [billingClient] is a strict (non-relaxed) mock everywhere — see the MockK
 * relaxed-nullable gotcha this project has already hit in CI (a relaxed mock
 * silently returning a non-null "empty" default caused a real OOM). Each test
 * stubs exactly the calls its scenario exercises.
 */
class PlayBillingClientImplTest {

    private val supporterRepository = mockk<SupporterRepository>(relaxed = true)
    private val billingClient = mockk<BillingClient>()

    private fun billingResult(code: Int): BillingResult = mockk {
        every { responseCode } returns code
    }

    /** Connection succeeds immediately and no purchases already exist — the common case. */
    private fun stubConnectionAndNoExistingPurchases() {
        every { billingClient.isReady } returns false
        every { billingClient.startConnection(any()) } answers {
            firstArg<BillingClientStateListener>().onBillingSetupFinished(billingResult(BillingClient.BillingResponseCode.OK))
        }
        every { billingClient.queryPurchasesAsync(any(), any()) } answers {
            secondArg<PurchasesResponseListener>()
                .onQueryPurchasesResponse(billingResult(BillingClient.BillingResponseCode.OK), emptyList())
        }
    }

    private fun stubProductDetails(responseCode: Int) {
        every { billingClient.queryProductDetailsAsync(any(), any()) } answers {
            secondArg<ProductDetailsResponseListener>().onProductDetailsResponse(
                billingResult(responseCode),
                QueryProductDetailsResult.create(emptyList(), emptyList()),
            )
        }
    }

    private fun buildClient(): PlayBillingClientImpl = PlayBillingClientImpl(
        supporterRepository = supporterRepository,
        // Unconfined so the init{}-launched connect/query coroutine (and any
        // coroutine this test triggers) runs synchronously to completion
        // before the next line of the test executes.
        externalScope = CoroutineScope(Dispatchers.Unconfined),
        billingClientFactory = { billingClient },
    )

    private fun purchase(products: List<String>, acknowledged: Boolean = false): Purchase = mockk {
        every { this@mockk.products } returns products
        every { purchaseState } returns Purchase.PurchaseState.PURCHASED
        every { isAcknowledged } returns acknowledged
        every { purchaseToken } returns "token-${products.first()}"
    }

    // ── Products not yet created in Play Console ────────────────────────────

    @Test
    fun `unknown product response reports products unavailable without crashing`() = runTest {
        stubConnectionAndNoExistingPurchases()
        stubProductDetails(BillingClient.BillingResponseCode.ITEM_UNAVAILABLE)

        val client = buildClient()

        assertFalse(client.observeProductsAvailable().first())
    }

    @Test
    fun `billing service unavailable during product query also reports unavailable`() = runTest {
        stubConnectionAndNoExistingPurchases()
        stubProductDetails(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE)

        val client = buildClient()

        assertFalse(client.observeProductsAvailable().first())
    }

    // ── Successful subscription purchase ────────────────────────────────────

    @Test
    fun `successful subscription purchase acknowledges it and marks the user a supporter`() = runTest {
        stubConnectionAndNoExistingPurchases()
        stubProductDetails(BillingClient.BillingResponseCode.ITEM_UNAVAILABLE)
        every { billingClient.acknowledgePurchase(any(), any()) } answers {
            secondArg<AcknowledgePurchaseResponseListener>()
                .onAcknowledgePurchaseResponse(billingResult(BillingClient.BillingResponseCode.OK))
        }

        val client = buildClient()
        val subscriptionPurchase = purchase(products = listOf(PlayBillingClientImpl.SUBSCRIPTION_PRODUCT_ID))

        client.onPurchasesUpdated(billingResult(BillingClient.BillingResponseCode.OK), listOf(subscriptionPurchase))

        verify(exactly = 1) {
            billingClient.acknowledgePurchase(
                match { it.purchaseToken == "token-${PlayBillingClientImpl.SUBSCRIPTION_PRODUCT_ID}" },
                any(),
            )
        }
        verify(exactly = 1) { supporterRepository.setSupporter(true) }
        assertTrue(client.observeSubscriptionActive().first())
    }

    @Test
    fun `already-acknowledged subscription purchase is not re-acknowledged but still marks supporter`() = runTest {
        stubConnectionAndNoExistingPurchases()
        stubProductDetails(BillingClient.BillingResponseCode.ITEM_UNAVAILABLE)

        val client = buildClient()
        val subscriptionPurchase = purchase(
            products = listOf(PlayBillingClientImpl.SUBSCRIPTION_PRODUCT_ID),
            acknowledged = true,
        )

        client.onPurchasesUpdated(billingResult(BillingClient.BillingResponseCode.OK), listOf(subscriptionPurchase))

        verify(exactly = 0) { billingClient.acknowledgePurchase(any(), any()) }
        verify(exactly = 1) { supporterRepository.setSupporter(true) }
    }

    // ── Successful tip purchase ──────────────────────────────────────────────

    @Test
    fun `successful tip purchase consumes it and marks the user a supporter`() = runTest {
        stubConnectionAndNoExistingPurchases()
        stubProductDetails(BillingClient.BillingResponseCode.ITEM_UNAVAILABLE)
        every { billingClient.consumeAsync(any(), any()) } answers {
            secondArg<ConsumeResponseListener>().onConsumeResponse(
                billingResult(BillingClient.BillingResponseCode.OK),
                "token-${PlayBillingClientImpl.TIP_PRODUCT_ID}",
            )
        }

        val client = buildClient()
        val tipPurchase = purchase(products = listOf(PlayBillingClientImpl.TIP_PRODUCT_ID))

        client.onPurchasesUpdated(billingResult(BillingClient.BillingResponseCode.OK), listOf(tipPurchase))

        verify(exactly = 1) {
            billingClient.consumeAsync(
                match { it.purchaseToken == "token-${PlayBillingClientImpl.TIP_PRODUCT_ID}" },
                any(),
            )
        }
        verify(exactly = 1) { supporterRepository.setSupporter(true) }
        // Tip is a consumable, not a subscription — it must not flip subscription-active.
        assertFalse(client.observeSubscriptionActive().first())
    }

    // ── Failure / cancellation paths ─────────────────────────────────────────

    @Test
    fun `user cancellation does not mark the user a supporter`() = runTest {
        stubConnectionAndNoExistingPurchases()
        stubProductDetails(BillingClient.BillingResponseCode.ITEM_UNAVAILABLE)

        val client = buildClient()
        client.onPurchasesUpdated(billingResult(BillingClient.BillingResponseCode.USER_CANCELED), null)

        verify(exactly = 0) { supporterRepository.setSupporter(any()) }
    }

    @Test
    fun `existing active subscription found on connect marks the user a supporter without a purchase flow`() = runTest {
        every { billingClient.isReady } returns false
        every { billingClient.startConnection(any()) } answers {
            firstArg<BillingClientStateListener>().onBillingSetupFinished(billingResult(BillingClient.BillingResponseCode.OK))
        }
        val existingSubscription = purchase(products = listOf(PlayBillingClientImpl.SUBSCRIPTION_PRODUCT_ID), acknowledged = true)
        every { billingClient.queryPurchasesAsync(any(), any()) } answers {
            secondArg<PurchasesResponseListener>()
                .onQueryPurchasesResponse(billingResult(BillingClient.BillingResponseCode.OK), listOf(existingSubscription))
        }
        stubProductDetails(BillingClient.BillingResponseCode.ITEM_UNAVAILABLE)

        buildClient()

        verify(exactly = 1) { supporterRepository.setSupporter(true) }
    }
}
