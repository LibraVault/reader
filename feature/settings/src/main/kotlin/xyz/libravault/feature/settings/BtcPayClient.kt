package xyz.libravault.feature.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

internal const val BTC_ADDRESS = "bc1q9y4q49lxnwrt9pnkgrxfpq92s9mvwv9espc5yg"
internal const val XMR_ADDRESS = "48LTe9fEF311sJ1syhC9oD8VcNqfjsLAo8WcmXYC8iJwg24cM6R2mydXSnQ18N2Q2jLU8qtc26rrpadUra6DDiTW82eVXWm"

private const val BASE_URL = "https://pay.libravault.xyz"
private const val STORE_ID = "Fr4b3j8B2CHMHsh2a4QT5QSKgNxT41r2ymz3F4RrcWxe"
private const val API_KEY = "REVOKED"

data class NewInvoice(val id: String, val checkoutLink: String)

data class InvoicePaymentInfo(
    val address: String,
    val paymentLink: String,
    val cryptoAmount: String,   // e.g. "0.00832907 XMR"
)

enum class InvoiceStatus { New, Processing, Settled, Expired, Invalid, Unknown }

@Singleton
class BtcPayClient @Inject constructor() {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mediaTypeJson = "application/json; charset=utf-8".toMediaType()

    suspend fun createInvoice(amountUsd: Int): NewInvoice = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("currency", "USD")
            put("amount", amountUsd)
        }.toString().toRequestBody(mediaTypeJson)
        val req = Request.Builder()
            .url("$BASE_URL/api/v1/stores/$STORE_ID/invoices")
            .addHeader("Authorization", "token $API_KEY")
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "Invoice creation failed (${resp.code})" }
            val obj = JSONObject(resp.body!!.string())
            NewInvoice(obj.getString("id"), obj.getString("checkoutLink"))
        }
    }

    suspend fun getInvoiceStatus(invoiceId: String): InvoiceStatus = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE_URL/api/v1/stores/$STORE_ID/invoices/$invoiceId")
            .addHeader("Authorization", "token $API_KEY")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext InvoiceStatus.Unknown
            when (JSONObject(resp.body!!.string()).getString("status")) {
                "New" -> InvoiceStatus.New
                "Processing" -> InvoiceStatus.Processing
                "Settled" -> InvoiceStatus.Settled
                "Expired" -> InvoiceStatus.Expired
                "Invalid" -> InvoiceStatus.Invalid
                else -> InvoiceStatus.Unknown
            }
        }
    }

    suspend fun hasAnySettledInvoice(): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE_URL/api/v1/stores/$STORE_ID/invoices?status=Settled&take=10")
            .addHeader("Authorization", "token $API_KEY")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext false
            JSONArray(resp.body!!.string()).length() > 0
        }
    }

    suspend fun getPaymentInfo(invoiceId: String, coinCode: String): InvoicePaymentInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE_URL/api/v1/stores/$STORE_ID/invoices/$invoiceId/payment-methods")
            .addHeader("Authorization", "token $API_KEY")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val methods = JSONArray(resp.body!!.string())
            val methodId = if (coinCode == "XMR") "XMR-CHAIN" else "BTC-CHAIN"
            for (i in 0 until methods.length()) {
                val m = methods.getJSONObject(i)
                if (m.getString("paymentMethodId") == methodId) {
                    val rawAmount = m.optString("amount", m.optString("due", ""))
                    val currency = m.optString("currency", coinCode)
                    return@withContext InvoicePaymentInfo(
                        address = m.getString("destination"),
                        paymentLink = m.optString("paymentLink", ""),
                        cryptoAmount = if (rawAmount.isNotEmpty()) "$rawAmount $currency" else "",
                    )
                }
            }
            null
        }
    }
}
