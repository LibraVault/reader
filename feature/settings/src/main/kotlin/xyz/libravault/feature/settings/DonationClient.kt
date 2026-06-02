package xyz.libravault.feature.settings

internal const val BTC_ADDRESS = "bc1q9y4q49lxnwrt9pnkgrxfpq92s9mvwv9espc5yg"
internal const val XMR_ADDRESS = "48LTe9fEF311sJ1syhC9oD8VcNqfjsLAo8WcmXYC8iJwg24cM6R2mydXSnQ18N2Q2jLU8qtc26rrpadUra6DDiTW82eVXWm"

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
