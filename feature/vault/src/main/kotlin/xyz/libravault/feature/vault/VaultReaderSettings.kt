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
 * No `scrollMode` field (unlike `ReaderSettings`): the vault PDF reader has
 * only one rendering mode (continuous scroll) — no second paginated
 * implementation exists to switch to, so a scroll-mode control would toggle
 * nothing. No `marginScale` either — `ReaderSettings` declares one but never
 * wires it to any UI control, a gap not worth carrying over.
 */
data class VaultReaderSettings(
    val theme: ReadingTheme = ReadingTheme.DARK,
    val fontSize: Float = 1.0f, // Multiplier: 0.8 – 2.0
    val fontFamily: VaultReaderFontFamily = VaultReaderFontFamily.SYSTEM,
    val lineSpacing: Float = 1.4f, // 1.0 – 2.5
)

enum class VaultReaderFontFamily(val displayName: String) {
    SYSTEM("System Default"),
    SERIF("Serif"),
    SANS_SERIF("Sans-serif"),
    MONOSPACE("Monospace"),
}
