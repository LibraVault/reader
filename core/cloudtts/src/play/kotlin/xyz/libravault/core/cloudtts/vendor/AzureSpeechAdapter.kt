package xyz.libravault.core.cloudtts.vendor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.libravault.core.cloudtts.CloudCredentialFields
import javax.inject.Inject

/**
 * Azure AI Speech text-to-speech — verified against Microsoft Learn's
 * "Text to speech API reference (REST)". Region-specific host
 * (`{region}.tts.speech.microsoft.com`, PRD explicitly calls this out —
 * see `CloudTtsFixedHosts`), `Ocp-Apim-Subscription-Key` header (not a
 * bearer token), SSML request body (`application/ssml+xml`), raw audio
 * bytes back. `X-Microsoft-OutputFormat` selects the audio codec/bitrate.
 *
 * Key validation uses a *different* host — Azure's short-lived-token
 * issuance endpoint (`{region}.api.cognitive.microsoft.com`) — because it's
 * a cheap auth-only call, unlike the synthesis endpoint.
 */
class AzureSpeechAdapter internal constructor(
    private val httpClient: OkHttpClient,
    private val testSpeechUrl: HttpUrl? = null,
    private val testTokenUrl: HttpUrl? = null,
) : VendorTtsAdapter {

    @Inject constructor(httpClient: OkHttpClient) : this(httpClient, testSpeechUrl = null, testTokenUrl = null)

    // testSpeechUrl/testTokenUrl are BASE overrides (like every other
    // adapter's testBaseUrl) — the real path is always appended on top,
    // never skipped, so a test pointed at MockWebServer still exercises the
    // real path-building logic.
    private fun speechUrl(region: String): HttpUrl =
        (testSpeechUrl ?: "https://${CloudTtsFixedHosts.azureSpeechHost(region)}/".toHttpUrl())
            .newBuilder().addPathSegments("cognitiveservices/v1").build()

    private fun tokenUrl(region: String): HttpUrl =
        (testTokenUrl ?: "https://${CloudTtsFixedHosts.azureTokenHost(region)}/".toHttpUrl())
            .newBuilder().addPathSegments("sts/v1.0/issueToken").build()

    override suspend fun synthesize(text: String, voiceId: String, credentials: Map<String, String>): Result<ByteArray> =
        runCatching {
            val apiKey = credentials.field(CloudCredentialFields.API_KEY)
            val region = credentials.field(CloudCredentialFields.REGION)
            val locale = localeFromVoiceId(voiceId)
            val ssml = "<speak version='1.0' xml:lang='$locale'>" +
                "<voice name='$voiceId'>${text.escapeXml()}</voice></speak>"
            val body = ssml.toRequestBody("application/ssml+xml".toMediaType())
            val request = Request.Builder()
                .url(speechUrl(region))
                .addHeader("Ocp-Apim-Subscription-Key", apiKey)
                .addHeader("X-Microsoft-OutputFormat", "audio-16khz-32kbitrate-mono-mp3")
                .post(body)
                .build()

            withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }.use { response ->
                if (!response.isSuccessful) error("Azure AI Speech returned HTTP ${response.code}")
                response.body?.bytes() ?: error("Azure AI Speech returned an empty body")
            }
        }

    override suspend fun validateKey(credentials: Map<String, String>): Result<Unit> = runCatching {
        val apiKey = credentials.field(CloudCredentialFields.API_KEY)
        val region = credentials.field(CloudCredentialFields.REGION)
        val request = Request.Builder()
            .url(tokenUrl(region))
            .addHeader("Ocp-Apim-Subscription-Key", apiKey)
            .post("".toRequestBody(null))
            .build()

        withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }.use { response ->
            if (!response.isSuccessful) error("Azure AI Speech key validation failed: HTTP ${response.code}")
        }
    }

    private fun String.escapeXml(): String = replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
