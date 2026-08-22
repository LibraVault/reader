package xyz.libravault.feature.reader

import xyz.libravault.core.ui.theme.ReadingTheme

data class ReaderSettings(
    val theme: ReadingTheme   = ReadingTheme.DARK,
    val fontSize: Float       = 1.0f,     // Multiplier: 0.8 – 2.0
    val fontFamily: FontFamily = FontFamily.SYSTEM,
    val lineSpacing: Float    = 1.4f,
    val marginScale: Float    = 1.0f,     // Multiplier for horizontal margins
    val scrollMode: ScrollMode = ScrollMode.PAGINATED,
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
