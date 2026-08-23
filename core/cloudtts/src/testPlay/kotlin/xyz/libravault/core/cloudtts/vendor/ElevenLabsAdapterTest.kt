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

class ElevenLabsAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var adapter: ElevenLabsAdapter

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        adapter = ElevenLabsAdapter(OkHttpClient(), testBaseUrl = server.url("/"))
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `synthesize sends xi-api-key header and the voice id in the path, returns raw audio bytes`() = runTest {
        val fakeAudio = byteArrayOf(1, 2, 3, 4)
        server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(fakeAudio)).build())

        val result = adapter.synthesize("hello world", "voice-123", mapOf(CloudCredentialFields.API_KEY to "sk-test"))

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().contentEquals(fakeAudio))

        val recorded = server.takeRequest()
        assertEquals("sk-test", recorded.headers["xi-api-key"])
        assertTrue(recorded.target.contains("voice-123"))
        assertTrue(recorded.body!!.utf8().contains("hello world"))
    }

    @Test
    fun `synthesize fails closed on a non-2xx response, does not throw`() = runTest {
        server.enqueue(MockResponse(code = 500))

        val result = adapter.synthesize("text", "voice", mapOf(CloudCredentialFields.API_KEY to "sk-test"))

        assertTrue(result.isFailure)
    }

    @Test
    fun `a slash in voiceId is percent-encoded as one opaque path segment, not split into extra path segments`() = runTest {
        server.enqueue(MockResponse(code = 200, body = "audio"))
        adapter.synthesize("hi", "abc/../admin", mapOf(CloudCredentialFields.API_KEY to "sk-test"))

        val recorded = server.takeRequest()
        // Must NOT resolve to /v1/text-to-speech/admin (path traversal) or any
        // extra segment — the whole voiceId is one percent-encoded segment.
        assertTrue(recorded.target.contains("abc%2F..%2Fadmin"), recorded.target)
    }

    @Test
    fun `synthesize fails closed when the api_key credential is missing`() = runTest {
        val result = adapter.synthesize("text", "voice", emptyMap())
        assertTrue(result.isFailure)
    }

    @Test
    fun `validateKey succeeds on a 200 from GET v1 user`() = runTest {
        server.enqueue(MockResponse(code = 200))

        val result = adapter.validateKey(mapOf(CloudCredentialFields.API_KEY to "sk-test"))

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertTrue(recorded.target.contains("v1/user"))
    }

    @Test
    fun `validateKey fails on 401`() = runTest {
        server.enqueue(MockResponse(code = 401))
        assertTrue(adapter.validateKey(mapOf(CloudCredentialFields.API_KEY to "sk-bad")).isFailure)
    }
}
