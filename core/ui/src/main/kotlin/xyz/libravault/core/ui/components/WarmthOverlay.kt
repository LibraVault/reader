package xyz.libravault.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import xyz.libravault.core.ui.theme.WarmthOverlayColor

/**
 * Maximum alpha [WarmthOverlayColor] is drawn at when [warmth] == 1f.
 *
 * Picked from [xyz.libravault.core.ui.theme.WarmthOverlayColor] composited over every
 * `ConcreteReadingTheme`'s `background`/`onBackground` pair (see
 * `WarmthOverlayContrastTest`) — Sepia is the worst case (it already has the tightest
 * headroom above the WCAG AA 4.5:1 floor, per `ColorSchemeContrastTest`'s own doc) and
 * still clears ~4.99:1 at this alpha. Raising this constant without re-running that
 * test can silently push Sepia below AA at max warmth.
 */
internal const val WARMTH_MAX_ALPHA = 0.32f

/**
 * Maps a `warmth` setting (0f..1f, see `ReaderSettings.warmth`/`VaultReaderSettings.warmth`)
 * to the alpha [WarmthOverlayColor] is drawn at. Extracted as a pure function (rather than
 * inlined into [WarmthOverlay]) so the mapping — including the out-of-range clamp — is
 * unit-testable without a Compose test host, per this repo's "pure helpers should be
 * internal, not private" convention.
 */
internal fun warmthOverlayAlpha(warmth: Float): Float = warmth.coerceIn(0f, 1f) * WARMTH_MAX_ALPHA

/**
 * Kobo/Kindle-style warmth / blue-light filter (#422), independent of the Dark / Light /
 * Sepia / AMOLED / System theme choice — a user on Light theme can still dial in warmth for
 * evening reading without switching themes entirely.
 *
 * Implemented as a translucent full-size overlay drawn on top of whatever the reader is
 * already showing — EPUB WebView, the native PDF page bitmap, or Markdown's Compose
 * rendering alike — rather than a per-format color override threaded through each
 * renderer's own preferences (e.g. Readium's `EpubPreferences.backgroundColor`/`textColor`).
 * This is deliberately the "simplest" mechanism called out in #422's own scope: it needs no
 * per-format wiring, updates instantly as the slider moves (no round-trip through a WebView
 * JS preferences apply), and — as a side effect — is the only thing in this app that visually
 * responds to a reading-theme-adjacent setting on PDF pages at all, since PDF pages are
 * pre-rendered bitmaps with no theme/color hook of their own (see `PdfReaderScreen`'s and
 * `VaultPdfReaderScreen`'s docs).
 *
 * Deliberately has **no** pointer input handling (no `clickable`/`pointerInput` modifier) —
 * an empty `Box` with only a `background` does not register a `PointerInputModifierNode`, so
 * Compose's hit-testing skips it entirely and taps/gestures on the reader content underneath
 * (page-turn taps, centre-tap toolbar toggle, PDF pinch-zoom) pass through unaffected.
 *
 * `warmth == 0f` renders nothing (fully transparent), so this composable is safe to always
 * place in the tree rather than conditionally gated by the caller.
 */
@Composable
fun WarmthOverlay(warmth: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(WarmthOverlayColor.copy(alpha = warmthOverlayAlpha(warmth))),
    )
}
