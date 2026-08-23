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
 * ElevenLabs text-to-speech — verified against
 * https://elevenlabs.io/docs/api-reference/text-to-speech/convert (not
 * assumed). `POST /v1/text-to-speech/{voice_id}`, header `xi-api-key`
 * (not a bearer token), JSON body `{"text": "..."}`, raw audio bytes back.
 */
class ElevenLabsAdapter internal constructor(
    private val httpClient: OkHttpClient,
    private val testBaseUrl: HttpUrl? = null,
) : VendorTtsAdapter {

    @Inject constructor(httpClient: OkHttpClient) : this(httpClient, testBaseUrl = null)

    private fun baseUrl(): HttpUrl = testBaseUrl ?: "https://${CloudTtsFixedHosts.ELEVENLABS}".toHttpUrl()

    override suspend fun synthesize(text: String, voiceId: String, credentials: Map<String, String>): Result<ByteArray> =
        runCatchingCancellable {
            val apiKey = credentials.field(CloudCredentialFields.API_KEY)
            // addPathSegment (singular) percent-encodes voiceId as ONE opaque
            // segment — addPathSegments(string) would instead split it on '/'
            // and treat a voiceId containing a slash as extra path segments.
            val url = baseUrl().newBuilder()
                .addPathSegments("v1/text-to-speech")
                .addPathSegment(voiceId)
                .build()
            val body = buildJsonObject { put("text", text) }.toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("xi-api-key", apiKey)
                .post(body)
                .build()

            httpClient.executeOrFail(request, "ElevenLabs").use { response ->
                response.body?.bytes() ?: error("ElevenLabs returned an empty body")
            }
        }

    override suspend fun validateKey(credentials: Map<String, String>): Result<Unit> = runCatchingCancellable {
        val apiKey = credentials.field(CloudCredentialFields.API_KEY)
        val url = baseUrl().newBuilder().addPathSegments("v1/user").build()
        val request = Request.Builder().url(url).addHeader("xi-api-key", apiKey).get().build()

        httpClient.executeOrFail(request, "ElevenLabs key validation").close()
    }
}
