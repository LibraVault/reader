package xyz.libravault.feature.reader

import xyz.libravault.core.ui.theme.PresetFontFamily
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
}

enum class ScrollMode {
    PAGINATED,   // Page-turn animation
    SCROLLING,   // Continuous vertical scroll
}

/** #419 — maps to/from core:ui's [PresetFontFamily] so [ReaderSettings] can be
 * compared against/set from a `ReadingPreset` without core:ui depending on
 * this feature-specific enum. Both enums share the same four cases 1:1. */
fun FontFamily.toPresetFontFamily(): PresetFontFamily = when (this) {
    FontFamily.SYSTEM     -> PresetFontFamily.SYSTEM
    FontFamily.SERIF      -> PresetFontFamily.SERIF
    FontFamily.SANS_SERIF -> PresetFontFamily.SANS_SERIF
    FontFamily.MONOSPACE  -> PresetFontFamily.MONOSPACE
}

fun PresetFontFamily.toFontFamily(): FontFamily = when (this) {
    PresetFontFamily.SYSTEM     -> FontFamily.SYSTEM
    PresetFontFamily.SERIF      -> FontFamily.SERIF
    PresetFontFamily.SANS_SERIF -> FontFamily.SANS_SERIF
    PresetFontFamily.MONOSPACE  -> FontFamily.MONOSPACE
}
