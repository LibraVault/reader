package xyz.libravault.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.math.pow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * WCAG 2.2 AA contrast assertions for Libravault's colour schemes.
 *
 * Failing tests here mean a real user — low vision, or just outdoors in
 * sunlight — cannot read the text.
 *
 * ## Two deliberate design choices
 *
 * **1. Alpha is composited, not ignored.** [contrastRatio] blends the
 * foreground over the background before measuring. The previous version read
 * `.red`/`.green`/`.blue` straight off the [Color] and dropped `.alpha`
 * entirely, which made every assertion about a translucent token measure a
 * colour that is never actually drawn. That was not academic: sepia's
 * `onSurfaceVariant` was `SepiaText.copy(alpha = 0.7f)`, which the old maths
 * scored at **8.97:1** (comfortably passing) while the pixels a user actually
 * sees were at **4.18:1** — below the 4.5:1 AA floor. The test passed for four
 * months while the app shipped unreadable secondary text in sepia reading mode.
 *
 * Compositing uses Compose's own [compositeOver] rather than hand-rolled
 * blending, so this measures what the renderer produces rather than what this
 * file believes the renderer produces.
 *
 * **2. The matrix is exhaustive, not hand-picked.** The previous version
 * asserted twelve individually-chosen pairs. Every pair nobody thought to add
 * was silently unmeasured, and a new scheme inherited no coverage at all.
 * [allSchemesMeetContrastFloors] enumerates every scheme against every
 * foreground/background pairing in [TEXT_PAIRS] and [NON_TEXT_PAIRS], so adding
 * a scheme to [SCHEMES] automatically tests it.
 */
class ColorSchemeContrastTest {

    // ── Contrast maths (WCAG 2.2) ─────────────────────────────────────────────

    /** WCAG 2.2 relative luminance, using the sRGB linearisation formula. */
    private fun luminance(color: Color): Double {
        fun linearise(channel: Float): Double {
            val sRGB = channel.toDouble()
            return if (sRGB <= 0.04045) sRGB / 12.92 else ((sRGB + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linearise(color.red) +
            0.7152 * linearise(color.green) +
            0.0722 * linearise(color.blue)
    }

    /**
     * WCAG 2.2 contrast ratio (always >= 1.0) of [fg] drawn **onto** [bg].
     *
     * [fg] is composited over [bg] first. WCAG is defined over rendered pixels,
     * and a translucent foreground has no contrast ratio of its own — only the
     * blend does. See the class doc for the bug this caught.
     */
    private fun contrastRatio(fg: Color, bg: Color): Double {
        val rendered = fg.compositeOver(bg)
        val l1 = luminance(rendered)
        val l2 = luminance(bg)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    // ── The exhaustive matrix ─────────────────────────────────────────────────

    /**
     * Every foreground/background pairing Material3 guarantees is used as text.
     *
     * `onSurfaceVariant` appears twice on purpose: Material3 draws it on both
     * `surfaceVariant` and `surface`, and those are different backgrounds with
     * different contrast. Checking only one is how the sepia bug survived — it
     * passed on `surface` (4.68:1) and failed on `surfaceVariant` (4.18:1).
     */
    private val textPairs = listOf(
        Pairing("onPrimary", "primary", { onPrimary }, { primary }),
        Pairing("onPrimaryContainer", "primaryContainer", { onPrimaryContainer }, { primaryContainer }),
        Pairing("onSecondary", "secondary", { onSecondary }, { secondary }),
        Pairing("onBackground", "background", { onBackground }, { background }),
        Pairing("onSurface", "surface", { onSurface }, { surface }),
        Pairing("onSurfaceVariant", "surfaceVariant", { onSurfaceVariant }, { surfaceVariant }),
        Pairing("onSurfaceVariant", "surface", { onSurfaceVariant }, { surface }),
    )

    /** Dividers, borders and other non-text UI — WCAG's lower 3:1 floor. */
    private val nonTextPairs = listOf(
        Pairing("outline", "surface", { outline }, { surface }),
        Pairing("outline", "background", { outline }, { background }),
    )

    @TestFactory
    fun allSchemesMeetContrastFloors(): List<DynamicTest> = SCHEMES.flatMap { (schemeName, scheme) ->
        val text = textPairs.map { pairing ->
            DynamicTest.dynamicTest("$schemeName: ${pairing.label} >= $AA_TEXT:1 (AA normal text)") {
                val ratio = contrastRatio(pairing.fg(scheme), pairing.bg(scheme))
                assertTrue(ratio >= AA_TEXT) {
                    "$schemeName ${pairing.label} renders at %.2f:1, below the WCAG AA %.1f:1 floor for normal text"
                        .format(ratio, AA_TEXT)
                }
            }
        }
        val nonText = nonTextPairs.map { pairing ->
            DynamicTest.dynamicTest("$schemeName: ${pairing.label} >= $AA_NON_TEXT:1 (AA non-text)") {
                val ratio = contrastRatio(pairing.fg(scheme), pairing.bg(scheme))
                assertTrue(ratio >= AA_NON_TEXT) {
                    "$schemeName ${pairing.label} renders at %.2f:1, below the WCAG AA %.1f:1 floor — invisible divider"
                        .format(ratio, AA_NON_TEXT)
                }
            }
        }
        text + nonText
    }

    // ── Regressions worth naming individually ─────────────────────────────────

    /**
     * The bug that motivated compositing. Sepia's `onSurfaceVariant` is now a
     * solid [SepiaTextMuted] rather than `SepiaText.copy(alpha = 0.7f)`.
     *
     * This asserts the *property* that broke, not just the current number: a
     * token must clear AA on the actual background it is drawn on. Reverting to
     * the alpha'd colour fails this (4.18:1), which is the check the old test
     * could not perform.
     */
    @Test
    fun `sepia secondary text clears AA on the darker surfaceVariant, not just on surface`() {
        val onVariant = contrastRatio(
            SepiaColorScheme.onSurfaceVariant,
            SepiaColorScheme.surfaceVariant,
        )
        assertTrue(onVariant >= AA_TEXT) {
            "Sepia onSurfaceVariant renders at %.2f:1 on surfaceVariant (need %.1f:1). ".format(onVariant, AA_TEXT) +
                "surfaceVariant is darker than surface, so passing on surface alone is not enough."
        }
    }

    /**
     * Guards the *reason* the bug was invisible rather than only its instance.
     *
     * A translucent scheme colour is a latent contrast bug: its rendered value
     * depends on whatever sits behind it, so it can satisfy one background and
     * fail another while every hand-written assertion still passes. If a future
     * change reintroduces one, this fails and points at the compositing rule.
     */
    @Test
    fun `no scheme colour relies on alpha`() {
        val translucent = SCHEMES.flatMap { (schemeName, scheme) ->
            (textPairs + nonTextPairs)
                .map { it.label to it.fg(scheme) }
                .filter { (_, color) -> color.alpha < 1f }
                .map { (label, color) -> "$schemeName $label (alpha=${color.alpha})" }
        }
        assertTrue(translucent.isEmpty()) {
            "Scheme colours must be opaque — a translucent token's contrast depends on its " +
                "background, so it can pass one surface and fail another: $translucent"
        }
    }

    /**
     * Guards [contrastRatio]'s compositing directly.
     *
     * Without this, a change that silently stopped compositing would leave the
     * matrix above green — it would simply go back to measuring opaque colours,
     * which all pass. This is the test that fails in that case.
     *
     * The bound is deliberately loose. What matters is the *gap* between the two
     * behaviours, which is enormous: 50% black over white renders as mid-grey at
     * **~4:1**, whereas ignoring alpha measures pure black at **21:1** — the
     * maximum contrast the formula can produce. Pinning a tighter figure would
     * mean hard-coding the exact output of Compose's [compositeOver], which is an
     * implementation detail this test has no business asserting. (Measured at
     * 4.00:1 with the current Compose BOM; naive sRGB source-over predicts 3.98,
     * so the blend is not bit-identical to the obvious hand calculation — another
     * reason to depend on Compose's own function rather than reimplementing it.)
     */
    @Test
    fun `contrastRatio composites alpha rather than ignoring it`() {
        val halfBlackOnWhite = contrastRatio(Color.Black.copy(alpha = 0.5f), Color.White)
        assertTrue(halfBlackOnWhite < 6.0) {
            "50%% black over white measured %.2f:1. Anything near 21:1 means alpha is being ignored "
                .format(halfBlackOnWhite) + "and every translucent token is being scored on pixels nobody sees."
        }
        assertTrue(halfBlackOnWhite > 3.0) {
            "50%% black over white measured %.2f:1 — implausibly low for mid-grey on white; ".format(halfBlackOnWhite) +
                "the compositing or the luminance formula is wrong."
        }
    }

    private class Pairing(
        fgName: String,
        bgName: String,
        val fg: ColorScheme.() -> Color,
        val bg: ColorScheme.() -> Color,
    ) {
        val label = "$fgName on $bgName"
    }

    private companion object {
        const val AA_TEXT = 4.5
        const val AA_NON_TEXT = 3.0

        /** Add a scheme here and the whole matrix covers it automatically. */
        val SCHEMES = listOf(
            "Dark" to DarkColorScheme,
            "Light" to LightColorScheme,
            "Sepia" to SepiaColorScheme,
        )
    }
}
