package xyz.libravault.core.tts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TtsDurationEstimatorTest {

    @Test
    fun `150 words at 1x estimates to 60 seconds`() {
        val text = List(150) { "word" }.joinToString(" ")
        assertEquals(60_000L, TtsDurationEstimator.estimateDurationMs(text, speed = 1.0f))
    }

    @Test
    fun `doubling speed halves the estimate`() {
        val text = List(300) { "word" }.joinToString(" ")
        val normal = TtsDurationEstimator.estimateDurationMs(text, speed = 1.0f)
        val doubled = TtsDurationEstimator.estimateDurationMs(text, speed = 2.0f)
        assertEquals(normal / 2, doubled)
    }

    @Test
    fun `halving speed doubles the estimate`() {
        val text = List(150) { "word" }.joinToString(" ")
        val normal = TtsDurationEstimator.estimateDurationMs(text, speed = 1.0f)
        val halved = TtsDurationEstimator.estimateDurationMs(text, speed = 0.5f)
        assertEquals(normal * 2, halved)
    }

    @Test
    fun `empty text floors to the minimum duration instead of zero`() {
        assertEquals(1_000L, TtsDurationEstimator.estimateDurationMs("", speed = 1.0f))
        assertEquals(1_000L, TtsDurationEstimator.estimateDurationMs("   ", speed = 1.0f))
    }

    @Test
    fun `a non-positive speed does not divide by zero or go negative`() {
        val text = "some words here"
        val estimate = TtsDurationEstimator.estimateDurationMs(text, speed = 0f)
        assertTrue(estimate > 0)
    }

    @Test
    fun `word count is whitespace-delimited regardless of run length`() {
        val text = "one   two\tthree\nfour"
        val estimate = TtsDurationEstimator.estimateDurationMs(text, speed = 1.0f)
        // 4 words at 150 wpm = 1.6 seconds
        assertEquals(1_600L, estimate)
    }
}
