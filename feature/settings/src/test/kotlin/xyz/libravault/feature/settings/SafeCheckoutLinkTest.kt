package xyz.libravault.feature.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Tests for [safeCheckoutLink] — the validator used in DonateScreen before
 * launching BTCPay's checkout URL via Intent.ACTION_VIEW.
 *
 * Defense against malicious BTCPay responses that try to phish the user
 * via javascript:, intent:, or non-HTTP schemes (review finding #23 / WS5.2).
 *
 * Uses java.net.URI under the hood (no Android runtime needed in tests).
 */
class SafeCheckoutLinkTest {

    @Test
    fun `valid https URL is accepted`() {
        val uri = safeCheckoutLink("https://checkout.btcpayserver.com/i/abc123")
        assertEquals("checkout.btcpayserver.com", uri?.host)
        assertEquals("/i/abc123", uri?.path)
    }

    @Test
    fun `HTTPS uppercase scheme is accepted`() {
        val uri = safeCheckoutLink("HTTPS://btcpay.example/checkout")
        assertEquals("btcpay.example", uri?.host)
    }

    @Test
    fun `http scheme is rejected (no TLS)`() {
        assertNull(safeCheckoutLink("http://checkout.btcpayserver.com/i/abc"))
    }

    @Test
    fun `javascript scheme is rejected`() {
        // java.net.URI rejects `javascript:alert(1)` outright because
        // alert(1) is not a valid hier-part — the runCatching catches it.
        assertNull(safeCheckoutLink("javascript:alert(1)"))
    }

    @Test
    fun `intent scheme is rejected`() {
        // intent: URIs are valid java.net.URI but our scheme allow-list
        // rejects them explicitly.
        val uri = safeCheckoutLink("intent:#Intent;action=android.intent.action.VIEW;end")
        // Either rejected by URI parsing or by scheme check — either way null
        assertNull(uri)
    }

    @Test
    fun `file scheme is rejected`() {
        assertNull(safeCheckoutLink("file:///sdcard/Download/exploit.apk"))
    }

    @Test
    fun `content scheme is rejected`() {
        assertNull(safeCheckoutLink("content://com.android.contacts/contacts/1"))
    }

    @Test
    fun `raw text is rejected (not a URL)`() {
        assertNull(safeCheckoutLink("click here for free bitcoin"))
    }

    @Test
    fun `empty string is rejected`() {
        assertNull(safeCheckoutLink(""))
    }

    @Test
    fun `whitespace-only is rejected`() {
        assertNull(safeCheckoutLink("   "))
    }

    @Test
    fun `URL with no host is rejected`() {
        // `https://` followed by an opaque hier-part — rejected by URI parsing.
        assertNull(safeCheckoutLink("https://"))
    }

    @Test
    fun `URL with query and fragment retains host`() {
        val uri = safeCheckoutLink("https://btcpay.example.com/dashboard/invoice?id=abc&token=xyz#section")
        assertEquals("btcpay.example.com", uri?.host)
        assertEquals("abc", uri?.getQuery()?.substringAfter("id=")?.substringBefore("&"))
    }

    @Test
    fun `fragment-only URL is rejected`() {
        // URI parses but has no scheme — fails scheme check.
        assertNull(safeCheckoutLink("https://#fragment"))
    }
}