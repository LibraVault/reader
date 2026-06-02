package xyz.libravault.feature.settings

import javax.inject.Inject
import javax.inject.Singleton

/**
 * F-Droid flavor: no network calls. Shows static BTC/XMR addresses only.
 * getInvoiceStatus returns Expired to stop any stale pending-invoice poll loops.
 */
@Singleton
class StaticDonationClient @Inject constructor() : DonationClient {
    override suspend fun createInvoice(amountUsd: Int) =
        NewInvoice(id = "", checkoutLink = "")

    override suspend fun getInvoiceStatus(invoiceId: String) = InvoiceStatus.Expired

    override suspend fun hasAnySettledInvoice() = false

    override suspend fun getPaymentInfo(invoiceId: String, coinCode: String) =
        InvoicePaymentInfo(
            address = if (coinCode == "XMR") XMR_ADDRESS else BTC_ADDRESS,
            paymentLink = "",
            cryptoAmount = "",
        )
}
