package xyz.libravault.core.licensing

import com.android.billingclient.api.Purchase
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PlayBillingProGate.isProPurchase] — the predicate that
 * decides whether a Play purchase unlocks Pro. Lives in the companion object
 * specifically so it's testable without constructing [PlayBillingProGate]
 * itself, whose constructor calls the real `BillingClient.newBuilder`.
 */
class PlayBillingProGateTest {

    private fun purchase(products: List<String>, state: Int) = mockk<Purchase>().also {
        every { it.products } returns products
        every { it.purchaseState } returns state
    }

    @Test
    fun `matching product id and PURCHASED state unlocks Pro`() {
        val purchase = purchase(
            products = listOf(PlayBillingProGate.PRODUCT_ID),
            state = Purchase.PurchaseState.PURCHASED,
        )

        assertTrue(PlayBillingProGate.isProPurchase(purchase))
    }

    @Test
    fun `matching product id but PENDING state does not unlock Pro`() {
        val purchase = purchase(
            products = listOf(PlayBillingProGate.PRODUCT_ID),
            state = Purchase.PurchaseState.PENDING,
        )

        assertFalse(PlayBillingProGate.isProPurchase(purchase))
    }

    @Test
    fun `unrelated product id does not unlock Pro even if purchased`() {
        val purchase = purchase(
            products = listOf("some_other_sku"),
            state = Purchase.PurchaseState.PURCHASED,
        )

        assertFalse(PlayBillingProGate.isProPurchase(purchase))
    }

    @Test
    fun `empty products list does not unlock Pro`() {
        val purchase = purchase(products = emptyList(), state = Purchase.PurchaseState.PURCHASED)

        assertFalse(PlayBillingProGate.isProPurchase(purchase))
    }
}
