package xyz.libravault.core.cloudtts

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.libravault.core.cloudtts.vendor.AmazonPollyAdapter
import xyz.libravault.core.cloudtts.vendor.AzureSpeechAdapter
import xyz.libravault.core.cloudtts.vendor.ElevenLabsAdapter
import xyz.libravault.core.cloudtts.vendor.GoogleCloudTtsAdapter
import xyz.libravault.core.cloudtts.vendor.OpenAiAdapter

/** Confirms RealCloudTtsProvider dispatches each CloudProviderId to the
 * matching adapter, not just that each adapter works in isolation (the
 * per-adapter test files already cover that). */
class RealCloudTtsProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: RealCloudTtsProvider

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient()
        val url = server.url("/")
        provider = RealCloudTtsProvider(
            elevenLabsAdapter = ElevenLabsAdapter(client, url),
            openAiAdapter = OpenAiAdapter(client, url),
            googleCloudTtsAdapter = GoogleCloudTtsAdapter(client, url),
            azureSpeechAdapter = AzureSpeechAdapter(client, testSpeechUrl = url, testTokenUrl = url),
            amazonPollyAdapter = AmazonPollyAdapter(client, url),
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `dispatches ELEVENLABS to the ElevenLabs adapter`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("audio"))
        val result = provider.synthesize(CloudProviderId.ELEVENLABS, "hi", "voice", mapOf(CloudCredentialFields.API_KEY to "k"))
        assertTrue(result.isSuccess)
        assertTrue(server.takeRequest().path!!.contains("text-to-speech"))
    }

    @Test
    fun `dispatches OPENAI to the OpenAI adapter`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("audio"))
        val result = provider.synthesize(CloudProviderId.OPENAI, "hi", "alloy", mapOf(CloudCredentialFields.API_KEY to "k"))
        assertTrue(result.isSuccess)
        assertTrue(server.takeRequest().path!!.contains("audio/speech"))
    }
}
