package xyz.libravault.feature.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val PROXY_URL = "https://libravault.xyz/donate"

@Singleton
class BtcPayClient @Inject constructor() : DonationClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mediaTypeJson = "application/json; charset=utf-8".toMediaType()

    override suspend fun createInvoice(amountUsd: Int): NewInvoice = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("amountUsd", amountUsd)
        }.toString().toRequestBody(mediaTypeJson)
        val req = Request.Builder()
            .url("$PROXY_URL/invoice")
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "Invoice creation failed (${resp.code})" }
            val obj = JSONObject(resp.body!!.string())
            NewInvoice(obj.getString("id"), obj.getString("checkoutLink"))
        }
    }

    override suspend fun getInvoiceStatus(invoiceId: String): InvoiceStatus = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$PROXY_URL/invoice/$invoiceId")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext InvoiceStatus.Unknown
            when (JSONObject(resp.body!!.string()).getString("status")) {
                "New"        -> InvoiceStatus.New
                "Processing" -> InvoiceStatus.Processing
                "Settled"    -> InvoiceStatus.Settled
                "Expired"    -> InvoiceStatus.Expired
                "Invalid"    -> InvoiceStatus.Invalid
                else         -> InvoiceStatus.Unknown
            }
        }
    }

    override suspend fun hasAnySettledInvoice(): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$PROXY_URL/settled")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext false
            JSONObject(resp.body!!.string()).optBoolean("settled", false)
        }
    }

    override suspend fun getPaymentInfo(invoiceId: String, coinCode: String): InvoicePaymentInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$PROXY_URL/invoice/$invoiceId/payment/$coinCode")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val body = resp.body!!.string()
            if (body == "null") return@withContext null
            val obj = JSONObject(body)
            InvoicePaymentInfo(
                address      = obj.getString("address"),
                paymentLink  = obj.optString("paymentLink", ""),
                cryptoAmount = obj.optString("cryptoAmount", ""),
            )
        }
    }
}
