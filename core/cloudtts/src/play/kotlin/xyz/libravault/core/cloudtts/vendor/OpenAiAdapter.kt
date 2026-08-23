package xyz.libravault.core.cloudtts.vendor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * OpenAI text-to-speech — verified against
 * https://platform.openai.com/docs/api-reference/audio/createSpeech.
 * `POST /v1/audio/speech`, `Authorization: Bearer` header, JSON body
 * `{"model", "input", "voice"}`, raw audio bytes back.
 */
class OpenAiAdapter internal constructor(
    private val httpClient: OkHttpClient,
    private val testBaseUrl: HttpUrl? = null,
) : VendorTtsAdapter {

    @Inject constructor(httpClient: OkHttpClient) : this(httpClient, testBaseUrl = null)

    private fun baseUrl(): HttpUrl = testBaseUrl ?: "https://${CloudTtsFixedHosts.OPENAI}".toHttpUrl()

    override suspend fun synthesize(text: String, voiceId: String, credentials: Map<String, String>): Result<ByteArray> =
        runCatching {
            val apiKey = credentials.field(CloudCredentialFields.API_KEY)
            val url = baseUrl().newBuilder().addPathSegments("v1/audio/speech").build()
            val body = buildJsonObject {
                put("model", "tts-1")
                put("input", text)
                put("voice", voiceId)
            }.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }.use { response ->
                if (!response.isSuccessful) error("OpenAI returned HTTP ${response.code}")
                response.body?.bytes() ?: error("OpenAI returned an empty body")
            }
        }

    override suspend fun validateKey(credentials: Map<String, String>): Result<Unit> = runCatching {
        val apiKey = credentials.field(CloudCredentialFields.API_KEY)
        val url = baseUrl().newBuilder().addPathSegments("v1/models").build()
        val request = Request.Builder().url(url).addHeader("Authorization", "Bearer $apiKey").get().build()

        withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }.use { response ->
            if (!response.isSuccessful) error("OpenAI key validation failed: HTTP ${response.code}")
        }
    }
}
