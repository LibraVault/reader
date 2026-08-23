package xyz.libravault.core.cloudtts.vendor

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.libravault.core.cloudtts.CloudCredentialFields
import javax.inject.Inject

/**
 * Amazon Polly text-to-speech — verified against AWS's SynthesizeSpeech API
 * docs. The one vendor here that isn't a bearer-token REST call: AWS
 * Signature Version 4 (SigV4), region-specific host
 * (`polly.{region}.amazonaws.com`), JSON body. See [AwsSigV4Signer]'s class
 * doc for how the signing implementation itself was verified.
 */
class AmazonPollyAdapter internal constructor(
    private val httpClient: OkHttpClient,
    private val testBaseUrl: HttpUrl? = null,
) : VendorTtsAdapter {

    @Inject constructor(httpClient: OkHttpClient) : this(httpClient, testBaseUrl = null)

    private fun baseUrl(region: String): HttpUrl =
        testBaseUrl ?: "https://${CloudTtsFixedHosts.pollyHost(region)}".toHttpUrl()

    override suspend fun synthesize(text: String, voiceId: String, credentials: Map<String, String>): Result<ByteArray> =
        runCatching {
            val accessKeyId = credentials.field(CloudCredentialFields.ACCESS_KEY_ID)
            val secretAccessKey = credentials.field(CloudCredentialFields.SECRET_ACCESS_KEY)
            val region = credentials.field(CloudCredentialFields.REGION)

            val payload = buildJsonObject {
                put("Text", text)
                put("VoiceId", voiceId)
                put("OutputFormat", "mp3")
                put("Engine", "neural")
            }.toString()

            val url = baseUrl(region).newBuilder().addPathSegments("v1/speech").build()
            val signed = AwsSigV4Signer.sign(
                method = "POST",
                host = url.host,
                canonicalUri = "/v1/speech",
                payload = payload,
                region = region,
                accessKeyId = accessKeyId,
                secretAccessKey = secretAccessKey,
                extraSignedHeaders = mapOf("content-type" to "application/json"),
            )
            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Amz-Date", signed.amzDate)
                .addHeader("Authorization", signed.authorizationHeader)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.executeOrFail(request, "Amazon Polly").use { response ->
                response.body?.bytes() ?: error("Amazon Polly returned an empty body")
            }
        }

    override suspend fun validateKey(credentials: Map<String, String>): Result<Unit> = runCatching {
        val accessKeyId = credentials.field(CloudCredentialFields.ACCESS_KEY_ID)
        val secretAccessKey = credentials.field(CloudCredentialFields.SECRET_ACCESS_KEY)
        val region = credentials.field(CloudCredentialFields.REGION)

        val url = baseUrl(region).newBuilder().addPathSegments("v1/voices").build()
        val signed = AwsSigV4Signer.sign(
            method = "GET",
            host = url.host,
            canonicalUri = "/v1/voices",
            payload = "",
            region = region,
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
        )
        val request = Request.Builder()
            .url(url)
            .addHeader("X-Amz-Date", signed.amzDate)
            .addHeader("Authorization", signed.authorizationHeader)
            .get()
            .build()

        httpClient.executeOrFail(request, "Amazon Polly key validation").close()
    }
}
