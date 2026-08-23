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

class AmazonPollyAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var adapter: AmazonPollyAdapter

    private val credentials = mapOf(
        CloudCredentialFields.ACCESS_KEY_ID to "AKIAEXAMPLE",
        CloudCredentialFields.SECRET_ACCESS_KEY to "supersecret",
        CloudCredentialFields.REGION to "us-east-1",
    )

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        adapter = AmazonPollyAdapter(OkHttpClient(), testBaseUrl = server.url("/"))
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `synthesize sends a SigV4 Authorization header and X-Amz-Date, returns raw audio bytes`() = runTest {
        val fakeAudio = byteArrayOf(3, 1, 4)
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(fakeAudio)))

        val result = adapter.synthesize("hello", "Joanna", credentials)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().contentEquals(fakeAudio))

        val recorded = server.takeRequest()
        val authHeader = recorded.headers["Authorization"]
        assertTrue(authHeader != null)
        assertTrue(authHeader!!.startsWith("AWS4-HMAC-SHA256 "))
        assertTrue(authHeader.contains("Credential=AKIAEXAMPLE/"))
        assertTrue(authHeader.contains("/us-east-1/polly/aws4_request"))
        assertTrue(authHeader.contains("SignedHeaders="))
        assertTrue(Regex("Signature=[0-9a-f]{64}$").containsMatchIn(authHeader))
        assertTrue(recorded.headers["X-Amz-Date"] != null)
        assertTrue(recorded.body.readUtf8().contains("\"VoiceId\":\"Joanna\""))
    }

    @Test
    fun `synthesize fails closed on a non-2xx response, such as an AWS signature mismatch in production`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        assertTrue(adapter.synthesize("text", "Joanna", credentials).isFailure)
    }

    @Test
    fun `synthesize fails closed when secret_access_key is missing`() = runTest {
        val incomplete = mapOf(CloudCredentialFields.ACCESS_KEY_ID to "AKIAEXAMPLE", CloudCredentialFields.REGION to "us-east-1")
        assertTrue(adapter.synthesize("text", "Joanna", incomplete).isFailure)
    }

    @Test
    fun `validateKey signs a GET to v1 voices`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val result = adapter.validateKey(credentials)
        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertTrue(recorded.path!!.contains("v1/voices"))
        assertTrue(recorded.headers["Authorization"]?.startsWith("AWS4-HMAC-SHA256 ") == true)
    }
}
