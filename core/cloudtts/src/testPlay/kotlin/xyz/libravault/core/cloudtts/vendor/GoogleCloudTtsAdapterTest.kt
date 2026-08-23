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
import java.util.Base64

class GoogleCloudTtsAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var adapter: GoogleCloudTtsAdapter

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        adapter = GoogleCloudTtsAdapter(OkHttpClient(), testBaseUrl = server.url("/"))
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `synthesize base64-decodes the JSON audioContent field, unlike the other four vendors' raw-bytes response`() = runTest {
        val fakeAudio = byteArrayOf(1, 2, 3)
        val base64Audio = Base64.getEncoder().encodeToString(fakeAudio)
        server.enqueue(MockResponse(code = 200, body = "{\"audioContent\":\"$base64Audio\"}"))

        val result = adapter.synthesize("hello", "en-US-Wavenet-D", mapOf(CloudCredentialFields.API_KEY to "goog-key"))

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().contentEquals(fakeAudio))

        val recorded = server.takeRequest()
        assertTrue(recorded.target.contains("key=goog-key"))
        assertTrue(recorded.body!!.utf8().contains("\"languageCode\":\"en-US\""), "expected locale derived from the voice id")
    }

    @Test
    fun `synthesize fails closed on a non-2xx response`() = runTest {
        server.enqueue(MockResponse(code = 403))
        assertTrue(adapter.synthesize("text", "en-US-Wavenet-D", mapOf(CloudCredentialFields.API_KEY to "bad")).isFailure)
    }

    @Test
    fun `synthesize fails closed when the api_key credential is missing`() = runTest {
        assertTrue(adapter.synthesize("text", "en-US-Wavenet-D", emptyMap()).isFailure)
    }

    @Test
    fun `synthesize tolerates unknown fields in the response JSON`() = runTest {
        val base64Audio = Base64.getEncoder().encodeToString(byteArrayOf(1))
        server.enqueue(MockResponse(code = 200, body = "{\"audioContent\":\"$base64Audio\",\"timepoints\":[]}"))
        val result = adapter.synthesize("hi", "en-US-Wavenet-D", mapOf(CloudCredentialFields.API_KEY to "k"))
        assertTrue(result.isSuccess, "an extra response field must not turn a successful synthesis into a failure")
    }

    @Test
    fun `validateKey calls GET v1 voices with the key as a query parameter`() = runTest {
        server.enqueue(MockResponse(code = 200))
        val result = adapter.validateKey(mapOf(CloudCredentialFields.API_KEY to "goog-key"))
        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertTrue(recorded.target.contains("v1/voices"))
    }
}
