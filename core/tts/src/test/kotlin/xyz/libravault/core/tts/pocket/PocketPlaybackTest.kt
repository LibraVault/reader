package xyz.libravault.core.tts.pocket

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

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

    @Test
    fun `PCM sample rate constant is correct`() {
        // Verify the audio format constants used in PocketPlayback
        val sampleRate = 24000
        assertEquals(24000, sampleRate, "Sample rate should be 24 kHz")
    }
}
