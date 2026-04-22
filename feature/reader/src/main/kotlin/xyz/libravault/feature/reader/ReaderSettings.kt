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
}

enum class ScrollMode {
    PAGINATED,   // Page-turn animation
    SCROLLING,   // Continuous vertical scroll
}
