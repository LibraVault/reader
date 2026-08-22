package xyz.libravault.feature.vault

import xyz.libravault.core.ui.theme.ReadingTheme

/**
 * Per-session reading settings for the vault-native EPUB reader — same shape
 * and defaults as `feature:reader`'s `ReaderSettings`, kept as a private
 * duplicate rather than a new cross-module dependency (same call
 * [VaultCoverPlaceholder]/[VaultBookmarksSheet] already made). Session-only,
 * matching `ReaderSettings`' own behavior exactly: nothing here is persisted,
 * it resets to these defaults every time a reader screen opens.
 *
 * `scrollMode` now has a paginated implementation to switch to on the PDF
 * side (see [VaultPdfReaderScreen]) — restored after having been dropped
 * when this type was introduced (Phase 5b had only continuous scroll). No
 * `marginScale` field though — `ReaderSettings` declares one but never wires
 * it to any UI control, a gap not worth carrying over.
 */
data class VaultReaderSettings(
    val theme: ReadingTheme = ReadingTheme.DARK,
    val fontSize: Float = 1.0f, // Multiplier: 0.8 – 2.0
    val fontFamily: VaultReaderFontFamily = VaultReaderFontFamily.SYSTEM,
    val lineSpacing: Float = 1.4f, // 1.0 – 2.5
    val scrollMode: VaultScrollMode = VaultScrollMode.PAGINATED,
)

enum class VaultReaderFontFamily(val displayName: String) {
    SYSTEM("System Default"),
    SERIF("Serif"),
    SANS_SERIF("Sans-serif"),
    MONOSPACE("Monospace"),
    // #423 — dyslexia-friendly typeface. See VaultReaderViewModel.onFontFamilyChanged
    // for the paired line-spacing bump this selection applies automatically.
    OPEN_DYSLEXIC("OpenDyslexic (dyslexia-friendly)"),
}

/**
 * Line-spacing multiplier applied automatically when [VaultReaderFontFamily.OPEN_DYSLEXIC]
 * is selected (#423) — same rationale as `feature:reader`'s
 * `DYSLEXIA_FRIENDLY_LINE_SPACING`, duplicated rather than shared for the same
 * "parallel, not shared" reason as the rest of this file.
 */
internal const val VAULT_DYSLEXIA_FRIENDLY_LINE_SPACING = 1.8f

/** Same two modes as `feature:reader`'s `ScrollMode` — duplicated rather than
 * shared, same rationale as [VaultReaderFontFamily] vs. `FontFamily`. */
enum class VaultScrollMode {
    PAGINATED,   // Page-turn animation
    SCROLLING,   // Continuous vertical scroll
}
