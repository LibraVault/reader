package xyz.libravault.core.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WCAG 2.2 AA contrast ratio assertions for Libravault colour schemes.
 *
 * These tests enforce that Material3 token pairs meet accessibility thresholds.
 * Failing tests mean a real user (low vision or situational) cannot read the text.
 */

class ColorSchemeContrastTest {

    /**
     * WCAG 2.2 relative luminance, using the sRGB linearisation formula.
     */
    private fun luminance(color: Color): Double {
        fun linearise(channel: Float): Double {
            val sRGB = channel.toDouble()
            return if (sRGB <= 0.04045) sRGB / 12.92
            else ((sRGB + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linearise(color.red) +
               0.7152 * linearise(color.green) +
               0.0722 * linearise(color.blue)
    }

    /** WCAG 2.2 contrast ratio (always >= 1.0). */
    private fun contrastRatio(fg: Color, bg: Color): Double {
        val l1 = luminance(fg)
        val l2 = luminance(bg)
        val lighter = maxOf(l1, l2)
        val darker  = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    // ── LIB-240: Dark mode onSurfaceVariant ──────────────────────────────────

    @Test
    fun `dark onSurfaceVariant meets WCAG AA normal-text contrast (4_5_1)`() {
        val ratio = contrastRatio(DarkColorScheme.onSurfaceVariant, DarkColorScheme.surfaceVariant)
        assertTrue(ratio >= 4.5, "Dark onSurfaceVariant contrast $ratio < 4.5:1 — fails WCAG AA")
    }

    // ── LIB-241: Outline visibility in both schemes ──────────────────────────

    @Test
    fun `dark outline on surface meets WCAG AA minimum non-text contrast (3_1)`() {
        val ratio = contrastRatio(DarkColorScheme.outline, DarkColorScheme.surface)
        assertTrue(ratio >= 3.0, "Dark outline contrast $ratio < 3:1 — invisible divider")
    }

    @Test
    fun `light outline on surface meets WCAG AA minimum non-text contrast (3_1)`() {
        val ratio = contrastRatio(LightColorScheme.outline, LightColorScheme.surface)
        assertTrue(ratio >= 3.0, "Light outline contrast $ratio < 3:1 — invisible divider")
    }

    @Test
    fun `sepia outline on surface meets WCAG AA minimum non-text contrast (3_1)`() {
        val ratio = contrastRatio(SepiaColorScheme.outline, SepiaColorScheme.surface)
        assertTrue(ratio >= 3.0, "Sepia outline contrast $ratio < 3:1 — invisible divider")
    }

    // ── LIB-247: Sepia onSurfaceVariant ──────────────────────────────────────

    @Test
    fun `sepia onSurfaceVariant meets WCAG AA normal-text contrast (4_5_1)`() {
        val ratio = contrastRatio(SepiaColorScheme.onSurfaceVariant, SepiaColorScheme.surfaceVariant)
        assertTrue(ratio >= 4.5, "Sepia onSurfaceVariant contrast $ratio < 4.5:1 — fails WCAG AA")
    }

    // ── Sanity checks for tokens that should already pass ────────────────────

    @Test
    fun `dark onSurface meets WCAG AA normal-text contrast (4_5_1)`() {
        val ratio = contrastRatio(DarkColorScheme.onSurface, DarkColorScheme.surface)
        assertTrue(ratio >= 4.5, "Dark onSurface contrast $ratio < 4.5:1 — regression?")
    }

    @Test
    fun `light onSurface meets WCAG AA normal-text contrast (4_5_1)`() {
        val ratio = contrastRatio(LightColorScheme.onSurface, LightColorScheme.surface)
        assertTrue(ratio >= 4.5, "Light onSurface contrast $ratio < 4.5:1 — regression?")
    }

    @Test
    fun `sepia onSurface meets WCAG AA normal-text contrast (4_5_1)`() {
        val ratio = contrastRatio(SepiaColorScheme.onSurface, SepiaColorScheme.surface)
        assertTrue(ratio >= 4.5, "Sepia onSurface contrast $ratio < 4.5:1 — regression?")
    }
}
