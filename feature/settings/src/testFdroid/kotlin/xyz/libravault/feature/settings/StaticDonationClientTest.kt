package xyz.libravault.feature.settings

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [StaticDonationClient] — the F-Droid, no-network
 * [DonationClient] implementation. Pins the documented behavior that made it
 * safe to swap in for [BtcPayClient][xyz.libravault.feature.settings.BtcPayClient]
 * on this flavor: no real invoice, no payment polling, and coin-specific
 * address routing.
 */
class StaticDonationClientTest {

    private val addresses = mockk<StaticDonationAddresses> {
        every { btc } returns "bc1qtest_static_btc_address"
        every { xmr } returns "48test_static_xmr_address"
    }
    private val client = StaticDonationClient(addresses)

    @Test
    fun `createInvoice always returns an empty invoice regardless of amount`() = runTest {
        val invoice = client.createInvoice(amountUsd = 5)

        assertEquals("", invoice.id)
        assertEquals("", invoice.checkoutLink)
        assertTrue(invoice.isStatic, "empty id must mark the invoice as static")
    }

    @Test
    fun `getInvoiceStatus always returns Expired to stop any stale poll loop`() = runTest {
        assertEquals(InvoiceStatus.Expired, client.getInvoiceStatus("any-invoice-id"))
        assertEquals(InvoiceStatus.Expired, client.getInvoiceStatus(""))
    }

    @Test
    fun `hasAnySettledInvoice always returns false`() = runTest {
        assertFalse(client.hasAnySettledInvoice())
    }

    @Test
    fun `getPaymentInfo routes XMR to the xmr static address`() = runTest {
        val info = client.getPaymentInfo(invoiceId = "inv-1", coinCode = "XMR")

        assertEquals("48test_static_xmr_address", info?.address)
        assertEquals("", info?.paymentLink)
        assertEquals("", info?.cryptoAmount)
    }

    @Test
    fun `getPaymentInfo routes BTC and any other coin code to the btc static address`() = runTest {
        assertEquals("bc1qtest_static_btc_address", client.getPaymentInfo("inv-1", "BTC")?.address)
        // Only "XMR" is special-cased — everything else falls back to btc.
        assertEquals("bc1qtest_static_btc_address", client.getPaymentInfo("inv-1", "unknown")?.address)
    }
}
