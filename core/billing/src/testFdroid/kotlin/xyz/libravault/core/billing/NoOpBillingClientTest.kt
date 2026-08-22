package xyz.libravault.core.billing

import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoOpBillingClientTest {

    private val client = NoOpBillingClient()

    @Test
    fun `is not supported`() {
        assertFalse(client.isSupported)
    }

    @Test
    fun `products are never available`() = runTest {
        assertFalse(client.observeProductsAvailable().first())
    }

    @Test
    fun `subscription is never active`() = runTest {
        assertFalse(client.observeSubscriptionActive().first())
    }

    @Test
    fun `subscription purchase is a no-op failure`() = runTest {
        val result = client.purchaseSubscription(mockk())
        assertTrue(result.isFailure)
    }

    @Test
    fun `tip purchase is a no-op failure`() = runTest {
        val result = client.purchaseOneTimeTip(mockk())
        assertTrue(result.isFailure)
    }
}
