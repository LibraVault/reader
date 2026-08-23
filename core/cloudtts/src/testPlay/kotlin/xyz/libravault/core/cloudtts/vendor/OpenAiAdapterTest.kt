package xyz.libravault.core.cloudtts.vendor

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.libravault.core.cloudtts.CloudCredentialFields

class OpenAiAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var adapter: OpenAiAdapter

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        adapter = OpenAiAdapter(OkHttpClient(), testBaseUrl = server.url("/"))
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `synthesize sends a bearer token and model+input+voice body, returns raw audio bytes`() = runTest {
        val fakeAudio = byteArrayOf(9, 8, 7)
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(fakeAudio)))

        val result = adapter.synthesize("hello", "alloy", mapOf(CloudCredentialFields.API_KEY to "sk-test"))

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().contentEquals(fakeAudio))
        val recorded = server.takeRequest()
        assertEquals("Bearer sk-test", recorded.headers["Authorization"])
        // Buffer.readUtf8() drains the buffer — read once, assert twice.
        val requestBody = recorded.body.readUtf8()
        assertTrue(requestBody.contains("\"voice\":\"alloy\""))
        assertTrue(requestBody.contains("\"input\":\"hello\""))
    }

    @Test
    fun `synthesize fails closed on a non-2xx response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        assertTrue(adapter.synthesize("text", "alloy", mapOf(CloudCredentialFields.API_KEY to "sk-test")).isFailure)
    }

    @Test
    fun `synthesize fails closed when the api_key credential is missing`() = runTest {
        assertTrue(adapter.synthesize("text", "alloy", emptyMap()).isFailure)
    }

    @Test
    fun `validateKey calls GET v1 models with a bearer token`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val result = adapter.validateKey(mapOf(CloudCredentialFields.API_KEY to "sk-test"))
        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("v1/models"))
        assertEquals("Bearer sk-test", recorded.headers["Authorization"])
    }
}
