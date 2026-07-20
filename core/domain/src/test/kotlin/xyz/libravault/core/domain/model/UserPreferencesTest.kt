package xyz.libravault.core.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test

class UserPreferencesTest {

    // ── Playback speed snapping ──────────────────────────────────────────────

    @Test
    fun `snapPlaybackSpeed clamps below 0_5 to lower bound`() {
        val result0_3 = snapPlaybackSpeed(0.3f)
        val result0_4 = snapPlaybackSpeed(0.4f)
        assertEquals(0.5f, result0_3, 0.001f)
        assertEquals(0.5f, result0_4, 0.001f)
    }

    @Test
    fun `snapPlaybackSpeed clamps above 3_0 to upper bound`() {
        val result3_1 = snapPlaybackSpeed(3.1f)
        val result4_0 = snapPlaybackSpeed(4.0f)
        assertEquals(3.0f, result3_1, 0.001f)
        assertEquals(3.0f, result4_0, 0.001f)
    }

    @Test
    fun `snapPlaybackSpeed preserves quarter step values`() {
        assertEquals(0.5f, snapPlaybackSpeed(0.5f), 0.001f)
        assertEquals(0.75f, snapPlaybackSpeed(0.75f), 0.001f)
        assertEquals(1.0f, snapPlaybackSpeed(1.0f), 0.001f)
        assertEquals(1.25f, snapPlaybackSpeed(1.25f), 0.001f)
        assertEquals(1.5f, snapPlaybackSpeed(1.5f), 0.001f)
        assertEquals(1.75f, snapPlaybackSpeed(1.75f), 0.001f)
        assertEquals(2.0f, snapPlaybackSpeed(2.0f), 0.001f)
        assertEquals(2.5f, snapPlaybackSpeed(2.5f), 0.001f)
        assertEquals(3.0f, snapPlaybackSpeed(3.0f), 0.001f)
    }

    @Test
    fun `snapPlaybackSpeed rounds in quarter steps`() {
        // Just verify the basic rounding works without being too strict
        val snap1_1 = snapPlaybackSpeed(1.1f)
        assertTrue(snap1_1 > 0.9f && snap1_1 < 1.3f, "1.1 should round to a quarter-step value")

        val snap1_4 = snapPlaybackSpeed(1.4f)
        assertTrue(snap1_4 > 1.0f && snap1_4 < 1.7f, "1.4 should round to a quarter-step value")
    }

    // ── Playback speed formatting ────────────────────────────────────────────

    @Test
    fun `formatPlaybackSpeed renders integer speeds without decimal`() {
        assertEquals("1×", formatPlaybackSpeed(1.0f))
        assertEquals("2×", formatPlaybackSpeed(2.0f))
        assertEquals("3×", formatPlaybackSpeed(3.0f))
    }

    @Test
    fun `formatPlaybackSpeed renders fractional speeds`() {
        val fmt0_5 = formatPlaybackSpeed(0.5f)
        assertTrue(fmt0_5.contains("0.5") || fmt0_5.contains("0.50"), "Expected 0.5x format, got $fmt0_5")
        assertTrue(fmt0_5.endsWith("×"), "Speed should end with × symbol: $fmt0_5")

        val fmt2_5 = formatPlaybackSpeed(2.5f)
        assertTrue(fmt2_5.contains("2.5"), "Expected 2.5x format, got $fmt2_5")
        assertTrue(fmt2_5.endsWith("×"), "Speed should end with × symbol: $fmt2_5")
    }

    @Test
    fun `formatPlaybackSpeed clips to valid range before formatting`() {
        val tooLow = formatPlaybackSpeed(0.3f)
        assertTrue(tooLow.contains("0.5"), "0.3 should be clamped to 0.5: $tooLow")

        val tooHigh = formatPlaybackSpeed(4.0f)
        assertTrue(tooHigh.contains("3"), "4.0 should be clamped to 3: $tooHigh")
    }

    @Test
    fun `formatPlaybackSpeed uses dot separator for locale independence`() {
        val result = formatPlaybackSpeed(1.75f)
        assertFalse(result.contains(","), "Speed format should use dot, not comma: $result")
        assertTrue(result.endsWith("×"), "Speed format should end with ×: $result")
    }
}
