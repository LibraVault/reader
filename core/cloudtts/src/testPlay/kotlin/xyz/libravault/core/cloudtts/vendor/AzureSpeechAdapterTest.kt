package xyz.libravault.core.cloudtts.vendor

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.libravault.core.cloudtts.CloudCredentialFields

class AzureSpeechAdapterTest {

    private lateinit var speechServer: MockWebServer
    private lateinit var tokenServer: MockWebServer
    private lateinit var adapter: AzureSpeechAdapter

    private val credentials = mapOf(
        CloudCredentialFields.API_KEY to "azure-key",
        CloudCredentialFields.REGION to "eastus",
    )

    @BeforeEach
    fun setUp() {
        speechServer = MockWebServer().apply { start() }
        tokenServer = MockWebServer().apply { start() }
        adapter = AzureSpeechAdapter(OkHttpClient(), testSpeechUrl = speechServer.url("/"), testTokenUrl = tokenServer.url("/"))
    }

    @AfterEach
    fun tearDown() {
        speechServer.close()
        tokenServer.close()
    }

    @Test
    fun `synthesize sends SSML with the subscription key header and output format, returns raw audio bytes`() = runTest {
        val fakeAudio = byteArrayOf(5, 6, 7)
        speechServer.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(fakeAudio)).build())

        val result = adapter.synthesize("hello & goodbye", "en-US-JennyNeural", credentials)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().contentEquals(fakeAudio))

        val recorded = speechServer.takeRequest()
        assertEquals("azure-key", recorded.headers["Ocp-Apim-Subscription-Key"])
        // OkHttp's toRequestBody() appends "; charset=utf-8" to the media type
        // by default — real Azure accepts that fine, so assert the prefix.
        assertTrue(recorded.headers["Content-Type"]?.startsWith("application/ssml+xml") == true)
        assertTrue(recorded.headers["X-Microsoft-OutputFormat"] != null)
        assertTrue(recorded.body!!.utf8().contains("en-US-JennyNeural"))
        assertTrue(recorded.body!!.utf8().contains("hello &amp; goodbye"), "text must be XML-escaped in the SSML body")
    }

    @Test
    fun `synthesize fails closed on a non-2xx response`() = runTest {
        speechServer.enqueue(MockResponse(code = 401))
        assertTrue(adapter.synthesize("text", "en-US-JennyNeural", credentials).isFailure)
    }

    @Test
    fun `synthesize fails closed when the region credential is missing`() = runTest {
        val result = adapter.synthesize("text", "en-US-JennyNeural", mapOf(CloudCredentialFields.API_KEY to "azure-key"))
        assertTrue(result.isFailure)
    }

    @Test
    fun `validateKey calls the token-issuance host, not the speech synthesis host`() = runTest {
        tokenServer.enqueue(MockResponse(code = 200, body = "fake-token"))
        val result = adapter.validateKey(credentials)
        assertTrue(result.isSuccess)
        val recorded = tokenServer.takeRequest()
        assertEquals("azure-key", recorded.headers["Ocp-Apim-Subscription-Key"])
        assertTrue(recorded.target.contains("issueToken"))
    }
}
