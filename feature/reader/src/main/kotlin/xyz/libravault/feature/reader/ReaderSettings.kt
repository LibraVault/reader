package xyz.libravault.feature.reader

import xyz.libravault.core.ui.theme.PresetFontFamily
import xyz.libravault.core.ui.theme.ReadingTheme

data class ReaderSettings(
    val theme: ReadingTheme   = ReadingTheme.DARK,
    val fontSize: Float       = 1.0f,     // Multiplier: 0.8 – 2.0
    val fontFamily: FontFamily = FontFamily.SYSTEM,
    val lineSpacing: Float    = 1.4f,
    // Multiplier for horizontal margins: 0.5 – 2.0. Wired to Readium's
    // EpubPreferences.pageMargins (#421) — see EpubReaderScreen.toEpubPreferences.
    // 1.0 is Readium's own "no scaling" default, so leaving this untouched is a
    // genuine no-op, not an approximation.
    val marginScale: Float    = 1.0f,
    // Text justification (#421) — maps to Readium's EpubPreferences.textAlign
    // (TextAlign.JUSTIFY) when true, left unset (current behaviour) when false.
    val justifyText: Boolean  = false,
    // Hyphenation (#421) — maps to Readium's EpubPreferences.hyphens.
    val hyphenation: Boolean  = false,
    val scrollMode: ScrollMode = ScrollMode.PAGINATED,
    // Kobo/Kindle-style warmth / blue-light filter (#422), independent of [theme] — 0f
    // (off) .. 1f (maximum). Session-only, same lifecycle as [fontSize]/[lineSpacing]:
    // resets to 0f every time the reader screen reopens, deliberately not persisted (see
    // #422's "Product decision" — no new UserPreferences/SharedPreferences plumbing).
    // See xyz.libravault.core.ui.components.WarmthOverlay for how this is rendered.
    val warmth: Float         = 0f,
    // Auto-scroll (#5) — continuously advances the reading position without manual
    // input, across all three formats (see AutoScroll.kt for the two mechanisms
    // this drives: continuous pixel scroll where a ScrollableState exists, timed
    // page-advance where it doesn't). Session-only, same lifecycle as scrollMode/
    // warmth above — resets to off every time the reader screen reopens.
    val autoScrollEnabled: Boolean = false,
    // Speed multiplier: 0.5 (slowest) – 3.0 (fastest), 1.0 = default pace. Same
    // multiplier shape as Read Aloud's TTS playback speed, for a consistent "speed"
    // mental model across the app's two hands-free reading modes.
    val autoScrollSpeed: Float = 1.0f,
)

enum class FontFamily(val displayName: String) {
    SYSTEM("System Default"),
    SERIF("Serif"),
    SANS_SERIF("Sans-serif"),
    MONOSPACE("Monospace"),
    // #423 — dyslexia-friendly typeface. See ReaderViewModel.onFontFamilyChanged
    // for the paired line-spacing bump this selection applies automatically.
    OPEN_DYSLEXIC("OpenDyslexic (dyslexia-friendly)"),
}

/**
 * Line-spacing multiplier applied automatically when [FontFamily.OPEN_DYSLEXIC]
 * is selected (#423) — dyslexia-friendly typography guidance recommends generous
 * line-height alongside the typeface itself, not the font alone. Within the
 * existing slider's 1.0–2.5 range; the user can still readjust afterward, which
 * falls back to a plain custom value (no "locked" state).
 */
internal const val DYSLEXIA_FRIENDLY_LINE_SPACING = 1.8f

enum class ScrollMode {
    PAGINATED,   // Page-turn animation
    SCROLLING,   // Continuous vertical scroll
}

/** #419 — maps to/from core:ui's [PresetFontFamily] so [ReaderSettings] can be
 * compared against/set from a `ReadingPreset` without core:ui depending on
 * this feature-specific enum. Both enums share the same four cases 1:1. */
fun FontFamily.toPresetFontFamily(): PresetFontFamily = when (this) {
    FontFamily.SYSTEM        -> PresetFontFamily.SYSTEM
    FontFamily.SERIF         -> PresetFontFamily.SERIF
    FontFamily.SANS_SERIF    -> PresetFontFamily.SANS_SERIF
    FontFamily.MONOSPACE     -> PresetFontFamily.MONOSPACE
    FontFamily.OPEN_DYSLEXIC -> PresetFontFamily.OPEN_DYSLEXIC
}

fun PresetFontFamily.toFontFamily(): FontFamily = when (this) {
    PresetFontFamily.SYSTEM        -> FontFamily.SYSTEM
    PresetFontFamily.SERIF         -> FontFamily.SERIF
    PresetFontFamily.SANS_SERIF    -> FontFamily.SANS_SERIF
    PresetFontFamily.MONOSPACE     -> FontFamily.MONOSPACE
    PresetFontFamily.OPEN_DYSLEXIC -> FontFamily.OPEN_DYSLEXIC
}
