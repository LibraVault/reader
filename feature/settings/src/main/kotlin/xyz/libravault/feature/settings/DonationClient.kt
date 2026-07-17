package xyz.libravault.feature.settings

data class NewInvoice(val id: String, val checkoutLink: String) {
    // Empty id signals a static (no-network) invoice — skip payment polling.
    val isStatic: Boolean get() = id.isEmpty()
}

data class InvoicePaymentInfo(
    val address: String,
    val paymentLink: String,
    val cryptoAmount: String,
)

enum class InvoiceStatus { New, Processing, Settled, Expired, Invalid, Unknown }

interface DonationClient {
    suspend fun createInvoice(amountUsd: Int): NewInvoice
    suspend fun getInvoiceStatus(invoiceId: String): InvoiceStatus
    suspend fun hasAnySettledInvoice(): Boolean
    suspend fun getPaymentInfo(invoiceId: String, coinCode: String): InvoicePaymentInfo?
}

/**
 * Static BTC / XMR donation addresses used when no live invoice can be
 * created (F-Droid flavor with no network, or Play flavor when BTCPay is
 * unreachable). Implemented by a Hilt-injected flavor-specific provider:
 *  - fdroid sourceSet ships the real addresses.
 *  - play sourceSet ships an empty impl (Play always tries BTCPay first).
 *
 * Was previously two `internal const val` literals in this file, which
 * meant they ended up in the Play APK's strings table — an unnecessary
 * information leak (review finding #16 / #9).
 */
interface StaticDonationAddresses {
    val btc: String
    val xmr: String
}
