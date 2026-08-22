package xyz.libravault.core.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.math.pow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import xyz.libravault.core.ui.theme.AmoledColorScheme
import xyz.libravault.core.ui.theme.DarkColorScheme
import xyz.libravault.core.ui.theme.LightColorScheme
import xyz.libravault.core.ui.theme.SepiaColorScheme
import xyz.libravault.core.ui.theme.WarmthOverlayColor

/**
 * WCAG 2.2 AA contrast assertions for [WarmthOverlay] (#422) — the explicit
 * acceptance criterion "should not defeat the WCAG AA contrast work from #266
 * — re-check contrast at the extremes of the warmth range" from the issue.
 *
 * Same maths as `ColorSchemeContrastTest` (compositing, not ignoring, alpha —
 * see that file's doc for the sepia bug this matters for) applied to one more
 * layer: [WarmthOverlayColor] drawn at [warmthOverlayAlpha]'s output composited
 * *on top of* each scheme's `background`/`onBackground` pair, since the overlay
 * sits visually above both — a slider at max warmth tints the text pixel and
 * the background pixel by the same blend, which is what actually gets rendered.
 */
class WarmthOverlayContrastTest {

    private fun luminance(color: Color): Double {
        fun linearise(channel: Float): Double {
            val sRGB = channel.toDouble()
            return if (sRGB <= 0.04045) sRGB / 12.92 else ((sRGB + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linearise(color.red) +
            0.7152 * linearise(color.green) +
            0.0722 * linearise(color.blue)
    }

    private fun contrastRatio(fg: Color, bg: Color): Double {
        val rendered = fg.compositeOver(bg)
        val l1 = luminance(rendered)
        val l2 = luminance(bg)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    /** The overlay drawn at [alpha], composited on top of [under]. */
    private fun warmed(under: Color, alpha: Float): Color =
        WarmthOverlayColor.copy(alpha = alpha).compositeOver(under)

    private val schemes = listOf(
        "Dark" to DarkColorScheme,
        "Light" to LightColorScheme,
        "Sepia" to SepiaColorScheme,
        "Amoled" to AmoledColorScheme,
    )

    @TestFactory
    fun `every scheme's background text pair clears AA at max warmth`(): List<DynamicTest> =
        schemes.map { (name, scheme) ->
            DynamicTest.dynamicTest("$name: onBackground on background >= $AA_TEXT:1 at warmth=1f") {
                val maxAlpha = warmthOverlayAlpha(1f)
                val bg = warmed(scheme.background, maxAlpha)
                val fg = warmed(scheme.onBackground, maxAlpha)
                val ratio = contrastRatio(fg, bg)
                assertTrue(ratio >= AA_TEXT) {
                    "$name renders onBackground-on-background at %.2f:1 at max warmth, below the WCAG AA %.1f:1 floor"
                        .format(ratio, AA_TEXT)
                }
            }
        }

    @Test
    fun `warmth of zero applies no tint`() {
        assertEquals(0f, warmthOverlayAlpha(0f))
    }

    @Test
    fun `warmth is clamped to 0f-1f before scaling to the max overlay alpha`() {
        assertEquals(warmthOverlayAlpha(1f), warmthOverlayAlpha(5f))
        assertEquals(warmthOverlayAlpha(0f), warmthOverlayAlpha(-2f))
    }

    @Test
    fun `warmth scales linearly to WARMTH_MAX_ALPHA`() {
        assertEquals(WARMTH_MAX_ALPHA, warmthOverlayAlpha(1f))
        assertEquals(WARMTH_MAX_ALPHA / 2f, warmthOverlayAlpha(0.5f), 0.0001f)
    }

    /**
     * Guards the actual number this whole test class depends on: if a future change
     * raises [WARMTH_MAX_ALPHA] without re-checking contrast, this documents what the
     * safe ceiling was measured at (Sepia is the worst case — see
     * `WarmthOverlay.kt`'s doc on [WARMTH_MAX_ALPHA]) rather than leaving that number
     * to tribal knowledge.
     */
    @Test
    fun `WARMTH_MAX_ALPHA is at or below the measured safe ceiling for sepia`() {
        assertTrue(WARMTH_MAX_ALPHA <= 0.33f) {
            "WARMTH_MAX_ALPHA raised to $WARMTH_MAX_ALPHA without re-verifying sepia still clears AA " +
                "(measured safe up to ~0.33f against a 4.5:1 floor as of this test)"
        }
    }

    private companion object {
        const val AA_TEXT = 4.5
    }
}
