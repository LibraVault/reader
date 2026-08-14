package xyz.libravault.core.tts.pocket.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Covers the measurement math behind the on-device Pocket TTS audio assertions
 * (issue #107) against synthetic waveforms with known properties.
 *
 * The instrumentation test that consumes these functions only runs on arm64
 * hardware, so without this the thresholds it relies on would have no CI
 * signal at all - a helper quietly reporting "silent" for real speech would
 * turn that test into one that can never fail.
 */
class PcmAnalysisTest {

    private val sampleRate = 22_050

    /** A full-scale sine wave: RMS is analytically amplitude / sqrt(2). */
    private fun sine(
        seconds: Double,
        frequency: Double = 440.0,
        amplitude: Float = 1.0f,
    ): FloatArray = FloatArray((seconds * sampleRate).toInt()) { i ->
        (amplitude * sin(2 * PI * frequency * i / sampleRate)).toFloat()
    }

    private fun silence(seconds: Double): FloatArray =
        FloatArray((seconds * sampleRate).toInt())

    // ── durationSeconds ──

    @Test
    fun `durationSeconds converts sample count at the given rate`() {
        assertEquals(1.0, PcmAnalysis.durationSeconds(22_050, 22_050), 1e-9)
        assertEquals(0.5, PcmAnalysis.durationSeconds(11_025, 22_050), 1e-9)
        assertEquals(0.0, PcmAnalysis.durationSeconds(0, 22_050), 1e-9)
    }

    @Test
    fun `durationSeconds rejects a non-positive sample rate`() {
        assertThrows<IllegalArgumentException> { PcmAnalysis.durationSeconds(100, 0) }
        assertThrows<IllegalArgumentException> { PcmAnalysis.durationSeconds(100, -1) }
    }

    // ── rms ──

    @Test
    fun `rms of a full-scale sine is amplitude over root two`() {
        assertEquals(1.0 / sqrt(2.0), PcmAnalysis.rms(sine(0.5)), 1e-3)
    }

    @Test
    fun `rms scales linearly with amplitude`() {
        val quiet = PcmAnalysis.rms(sine(0.5, amplitude = 0.1f))
        val loud = PcmAnalysis.rms(sine(0.5, amplitude = 1.0f))

        assertEquals(0.1, quiet / loud, 1e-3)
    }

    @Test
    fun `rms of digital silence is zero`() {
        assertEquals(0.0, PcmAnalysis.rms(silence(0.5)), 1e-12)
    }

    @Test
    fun `rms of an empty range is zero rather than NaN`() {
        assertEquals(0.0, PcmAnalysis.rms(FloatArray(0)), 1e-12)
        assertEquals(0.0, PcmAnalysis.rms(sine(0.1), from = 10, to = 10), 1e-12)
    }

    @Test
    fun `rms measures only the requested range`() {
        // Second half loud, first half silent.
        val samples = silence(0.25) + sine(0.25)
        val half = samples.size / 2

        assertEquals(0.0, PcmAnalysis.rms(samples, from = 0, to = half), 1e-12)
        assertEquals(1.0 / sqrt(2.0), PcmAnalysis.rms(samples, from = half, to = samples.size), 1e-3)
    }

    @Test
    fun `rms rejects out-of-bounds ranges`() {
        val samples = sine(0.1)

        assertThrows<IllegalArgumentException> { PcmAnalysis.rms(samples, from = -1) }
        assertThrows<IllegalArgumentException> { PcmAnalysis.rms(samples, to = samples.size + 1) }
        assertThrows<IllegalArgumentException> { PcmAnalysis.rms(samples, from = 10, to = 5) }
    }

    // ── peak ──

    @Test
    fun `peak reports the largest magnitude regardless of sign`() {
        assertEquals(0.8, PcmAnalysis.peak(floatArrayOf(0.1f, -0.8f, 0.3f)), 1e-6)
    }

    @Test
    fun `peak of an empty buffer is zero`() {
        assertEquals(0.0, PcmAnalysis.peak(FloatArray(0)), 1e-12)
    }

    // ── hasNonFiniteSamples / outOfRangeCount ──

    @Test
    fun `hasNonFiniteSamples detects NaN and infinities`() {
        assertFalse(PcmAnalysis.hasNonFiniteSamples(sine(0.1)))
        assertTrue(PcmAnalysis.hasNonFiniteSamples(floatArrayOf(0.1f, Float.NaN)))
        assertTrue(PcmAnalysis.hasNonFiniteSamples(floatArrayOf(0.1f, Float.POSITIVE_INFINITY)))
        assertTrue(PcmAnalysis.hasNonFiniteSamples(floatArrayOf(0.1f, Float.NEGATIVE_INFINITY)))
    }

    @Test
    fun `outOfRangeCount counts only samples beyond the limit`() {
        val samples = floatArrayOf(0.5f, 1.0f, -1.0f, 1.2f, -1.5f)

        // Exactly +-1.0 is in range; only the two overshoots count.
        assertEquals(2, PcmAnalysis.outOfRangeCount(samples))
        assertEquals(0, PcmAnalysis.outOfRangeCount(samples, limit = 2.0f))
    }

    @Test
    fun `outOfRangeCount ignores non-finite samples so the two checks stay independent`() {
        assertEquals(1, PcmAnalysis.outOfRangeCount(floatArrayOf(1.5f, Float.NaN)))
    }

    // ── silentFraction ──

    @Test
    fun `silentFraction is one for digital silence and zero for continuous tone`() {
        assertEquals(1.0, PcmAnalysis.silentFraction(silence(1.0), sampleRate), 1e-9)
        assertEquals(0.0, PcmAnalysis.silentFraction(sine(1.0), sampleRate), 1e-9)
    }

    @Test
    fun `silentFraction reports the proportion of a half-silent buffer`() {
        val samples = sine(0.5) + silence(0.5)

        assertEquals(0.5, PcmAnalysis.silentFraction(samples, sampleRate), 0.01)
    }

    @Test
    fun `silentFraction treats very quiet noise above the floor as audible`() {
        // Amplitude 0.01 -> RMS ~0.007, comfortably above the 1e-3 default.
        val samples = sine(0.5, amplitude = 0.01f)

        assertEquals(0.0, PcmAnalysis.silentFraction(samples, sampleRate), 1e-9)
    }

    @Test
    fun `silentFraction honours a custom silence threshold`() {
        val samples = sine(0.5, amplitude = 0.01f)

        // Raising the floor above this tone's RMS reclassifies it as silence.
        assertEquals(1.0, PcmAnalysis.silentFraction(samples, sampleRate, silenceRms = 0.05), 1e-9)
    }

    @Test
    fun `silentFraction reports an empty buffer as fully silent`() {
        assertEquals(1.0, PcmAnalysis.silentFraction(FloatArray(0), sampleRate), 1e-9)
    }

    @Test
    fun `silentFraction weights a trailing partial window by its real length`() {
        // 30 ms of tone with a 20 ms window: one full window plus a 10 ms
        // remainder. Counting windows rather than samples would misreport this.
        val samples = sine(0.03)

        assertEquals(0.0, PcmAnalysis.silentFraction(samples, sampleRate), 1e-9)
    }

    @Test
    fun `silentFraction rejects invalid window sizes`() {
        assertThrows<IllegalArgumentException> {
            PcmAnalysis.silentFraction(sine(0.1), sampleRate, windowMs = 0)
        }
        assertThrows<IllegalArgumentException> {
            PcmAnalysis.silentFraction(sine(0.1), sampleRate = 0)
        }
    }

    // ── longestSilenceSeconds ──

    @Test
    fun `longestSilenceSeconds finds the longer of two silent stretches`() {
        val samples = sine(0.2) + silence(0.1) + sine(0.2) + silence(0.4) + sine(0.2)

        assertEquals(0.4, PcmAnalysis.longestSilenceSeconds(samples, sampleRate), 0.03)
    }

    @Test
    fun `longestSilenceSeconds is zero for continuous tone`() {
        assertEquals(0.0, PcmAnalysis.longestSilenceSeconds(sine(1.0), sampleRate), 1e-9)
    }

    @Test
    fun `longestSilenceSeconds spans the whole buffer when it is all silence`() {
        assertEquals(1.0, PcmAnalysis.longestSilenceSeconds(silence(1.0), sampleRate), 0.03)
    }

    @Test
    fun `longestSilenceSeconds is zero for an empty buffer`() {
        assertEquals(0.0, PcmAnalysis.longestSilenceSeconds(FloatArray(0), sampleRate), 1e-9)
    }
}
