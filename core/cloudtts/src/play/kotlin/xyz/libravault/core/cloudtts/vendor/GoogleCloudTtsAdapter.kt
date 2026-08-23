package xyz.libravault.core.cloudtts.vendor

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.libravault.core.cloudtts.CloudCredentialFields
import java.util.Base64
import javax.inject.Inject

/**
 * Google Cloud Text-to-Speech — verified against
 * https://cloud.google.com/text-to-speech/docs/reference/rest/v1/text/synthesize.
 * `POST /v1/text:synthesize?key={apiKey}` (simple-API-key auth, not OAuth —
 * the right fit for a paste-your-key BYOK UX). Unlike the other four
 * vendors, the response is JSON with base64-encoded audio
 * (`{"audioContent": "..."}`), not raw bytes — this is the one adapter that
 * must decode it.
 */
class GoogleCloudTtsAdapter internal constructor(
    private val httpClient: OkHttpClient,
    private val testBaseUrl: HttpUrl? = null,
) : VendorTtsAdapter {

    @Inject constructor(httpClient: OkHttpClient) : this(httpClient, testBaseUrl = null)

    @Serializable
    private data class SynthesizeResponse(val audioContent: String)

    // ignoreUnknownKeys: Google's response may carry fields beyond
    // audioContent (e.g. timepoints) depending on the request — a strict
    // decoder would turn a technically-successful synthesis into a spurious
    // failure the moment Google's response shape gains a field this adapter
    // doesn't care about.
    private val json = Json { ignoreUnknownKeys = true }

    private fun baseUrl(): HttpUrl = testBaseUrl ?: "https://${CloudTtsFixedHosts.GOOGLE_CLOUD_TTS}".toHttpUrl()

    override suspend fun synthesize(text: String, voiceId: String, credentials: Map<String, String>): Result<ByteArray> =
        runCatching {
            val apiKey = credentials.field(CloudCredentialFields.API_KEY)
            val url = baseUrl().newBuilder()
                .addPathSegments("v1/text:synthesize")
                .addQueryParameter("key", apiKey)
                .build()
            val body = buildJsonObject {
                put("input", buildJsonObject { put("text", text) })
                put(
                    "voice",
                    buildJsonObject {
                        put("languageCode", localeFromVoiceId(voiceId))
                        put("name", voiceId)
                    },
                )
                put("audioConfig", buildJsonObject { put("audioEncoding", "MP3") })
            }.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(body).build()

            httpClient.executeOrFail(request, "Google Cloud TTS").use { response ->
                val responseJson = response.body?.string() ?: error("Google Cloud TTS returned an empty body")
                val decoded = json.decodeFromString<SynthesizeResponse>(responseJson)
                Base64.getDecoder().decode(decoded.audioContent)
            }
        }

    override suspend fun validateKey(credentials: Map<String, String>): Result<Unit> = runCatching {
        val apiKey = credentials.field(CloudCredentialFields.API_KEY)
        val url = baseUrl().newBuilder().addPathSegments("v1/voices").addQueryParameter("key", apiKey).build()
        val request = Request.Builder().url(url).get().build()

        httpClient.executeOrFail(request, "Google Cloud TTS key validation").close()
    }
}
