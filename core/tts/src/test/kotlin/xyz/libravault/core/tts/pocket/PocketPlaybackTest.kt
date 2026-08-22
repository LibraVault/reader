package xyz.libravault.core.tts.pocket

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class PocketPlaybackTest {

    @Test
    fun `PocketPlayback can be instantiated`() {
        val playback = PocketPlayback()
        assertNotNull(playback)
    }

    @Test
    fun `PocketPlayback has pause and resume methods`() {
        val playback = PocketPlayback()
        playback.pause()
        playback.resume()
        playback.stop()
        // No exception = success
    }

    @Test
    fun `float to short range clamping works`() {
        // Test that out-of-range values are clamped to 16-bit range
        val overPositive = (2.0f * 32767f).coerceIn(-32768f, 32767f).toInt()
        val maxPositive = (1.0f * 32767f).coerceIn(-32768f, 32767f).toInt()
        val zero = (0.0f * 32767f).coerceIn(-32768f, 32767f).toInt()
        val maxNegative = (-1.0f * 32767f).coerceIn(-32768f, 32767f).toInt()
        val overNegative = (-2.0f * 32767f).coerceIn(-32768f, 32767f).toInt()

        assertEquals(32767, overPositive)      // Clamped to max
        assertEquals(32767, maxPositive)       // Max positive
        assertEquals(0, zero)                  // Zero unchanged
        assertEquals(-32767, maxNegative)      // Max negative (approx)
        assertEquals(-32768, overNegative)     // Clamped to min
    }

    /**
     * [PocketPlayback.play] used to build its AudioTrack at a hardcoded 24000
     * Hz - the rate of sherpa-onnx's own "Pocket TTS" models, which this app
     * does not ship. The bundled LJSpeech Piper voice generates at 22050, so
     * playback ran ~8.8% fast and a semitone and a half sharp.
     *
     * The fix was to make the rate a required parameter sourced from
     * `OfflineTts.sampleRate()`, so there is no constant left to drift. What
     * this test pins down is the other half: that the *bundled model* is still
     * the 22.05 kHz voice everything assumes. Swapping in a voice at a
     * different rate is fine - playback follows the model now - but it should
     * be a deliberate, visible change, and it invalidates the duration bands
     * in PocketTtsAudioOutputTest.
     *
     * The replaced test asserted a local variable against itself and never
     * referenced PocketPlayback, so it could not have caught the bug.
     */
    @Test
    fun `bundled voice model still declares the 22_05 kHz sample rate`() {
        // Gradle runs unit tests with the module directory as CWD.
        val modelJson = File("src/main/assets/pocket-tts-model/en_US-ljspeech-high.onnx.json")
        assertTrue(modelJson.isFile, "Bundled model config missing at ${modelJson.absolutePath}")

        // Deliberately a narrow regex rather than a JSON dependency - this
        // module has no JSON parser on its test classpath, and the field is
        // unambiguous.
        val declaredRate = Regex("\"sample_rate\"\\s*:\\s*(\\d+)")
            .find(modelJson.readText())
            ?.groupValues
            ?.get(1)
            ?.toInt()

        assertEquals(22050, declaredRate, "Bundled voice model sample rate changed")
    }
}
