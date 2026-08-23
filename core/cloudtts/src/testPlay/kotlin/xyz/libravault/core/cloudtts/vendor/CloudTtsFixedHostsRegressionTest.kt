package xyz.libravault.core.cloudtts.vendor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * PRD §7: "Regression test pinning the five fixed endpoint hosts, so a
 * future change can't silently add a sixth or turn one into a
 * user-configurable value without that being a deliberate, reviewed diff."
 * Every assertion here failing is the point — changing any of these values
 * must show up as a diff to THIS file in code review, not just a diff
 * buried inside an adapter.
 */
class CloudTtsFixedHostsRegressionTest {

    @Test
    fun `ElevenLabs host is pinned`() {
        assertEquals("api.elevenlabs.io", CloudTtsFixedHosts.ELEVENLABS)
    }

    @Test
    fun `OpenAI host is pinned`() {
        assertEquals("api.openai.com", CloudTtsFixedHosts.OPENAI)
    }

    @Test
    fun `Google Cloud TTS host is pinned`() {
        assertEquals("texttospeech.googleapis.com", CloudTtsFixedHosts.GOOGLE_CLOUD_TTS)
    }

    @Test
    fun `Azure Speech host pattern is pinned to the Microsoft Cognitive Services domain`() {
        assertEquals("eastus.tts.speech.microsoft.com", CloudTtsFixedHosts.azureSpeechHost("eastus"))
        assertEquals("eastus.api.cognitive.microsoft.com", CloudTtsFixedHosts.azureTokenHost("eastus"))
    }

    @Test
    fun `Amazon Polly host pattern is pinned to the AWS domain`() {
        assertEquals("polly.us-east-1.amazonaws.com", CloudTtsFixedHosts.pollyHost("us-east-1"))
    }
}
