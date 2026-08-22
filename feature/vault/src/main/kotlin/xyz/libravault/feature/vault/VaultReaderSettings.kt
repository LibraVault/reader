package xyz.libravault.feature.vault

import xyz.libravault.core.ui.theme.PresetFontFamily
import xyz.libravault.core.ui.theme.ReadingTheme

/**
 * Per-session reading settings for the vault-native EPUB reader — same shape
 * and defaults as `feature:reader`'s `ReaderSettings`, kept as a private
 * duplicate rather than a new cross-module dependency (same call
 * [VaultCoverPlaceholder]/[VaultBookmarksSheet] already made). Font size,
 * font family, line spacing and scroll mode are session-only, matching
 * `ReaderSettings`' own behavior: nothing there is persisted, it resets to
 * these defaults every time a reader screen opens. `theme` is the one
 * exception (#428) — [VaultReaderViewModel] seeds and writes it through to
 * the global default via `core:storage`'s `ReadingThemePreference`.
 *
 * `scrollMode` now has a paginated implementation to switch to on the PDF
 * side (see [VaultPdfReaderScreen]) — restored after having been dropped
 * when this type was introduced (Phase 5b had only continuous scroll).
 *
 * `marginScale`/`justifyText`/`hyphenation` (#421) now mirror
 * `ReaderSettings`' fields 1:1 — the "gap not worth carrying over" this doc
 * used to describe (an unwired `marginScale`) is exactly what #421 wires up,
 * for both readers at once.
 */
data class VaultReaderSettings(
    val theme: ReadingTheme = ReadingTheme.DARK,
    val fontSize: Float = 1.0f, // Multiplier: 0.8 – 2.0
    val fontFamily: VaultReaderFontFamily = VaultReaderFontFamily.SYSTEM,
    val lineSpacing: Float = 1.4f, // 1.0 – 2.5
    val marginScale: Float = 1.0f, // Multiplier for horizontal margins: 0.5 – 2.0
    val justifyText: Boolean = false,
    val hyphenation: Boolean = false,
    val scrollMode: VaultScrollMode = VaultScrollMode.PAGINATED,
    // Kobo/Kindle-style warmth / blue-light filter (#422), independent of [theme] — 0f
    // (off) .. 1f (maximum). Session-only, same lifecycle as [fontSize]/[lineSpacing]:
    // resets to 0f every time the reader screen reopens, deliberately not persisted (see
    // #422's "Product decision" — no new UserPreferences/SharedPreferences plumbing).
    // See xyz.libravault.core.ui.components.WarmthOverlay for how this is rendered.
    val warmth: Float = 0f,
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

/** #419 — maps to/from core:ui's [PresetFontFamily] so [VaultReaderSettings]
 * can be compared against/set from a `ReadingPreset` without core:ui
 * depending on this feature-specific enum. Both enums share the same four
 * cases 1:1. */
fun VaultReaderFontFamily.toPresetFontFamily(): PresetFontFamily = when (this) {
    VaultReaderFontFamily.SYSTEM        -> PresetFontFamily.SYSTEM
    VaultReaderFontFamily.SERIF         -> PresetFontFamily.SERIF
    VaultReaderFontFamily.SANS_SERIF    -> PresetFontFamily.SANS_SERIF
    VaultReaderFontFamily.MONOSPACE     -> PresetFontFamily.MONOSPACE
    VaultReaderFontFamily.OPEN_DYSLEXIC -> PresetFontFamily.OPEN_DYSLEXIC
}

fun PresetFontFamily.toVaultReaderFontFamily(): VaultReaderFontFamily = when (this) {
    PresetFontFamily.SYSTEM        -> VaultReaderFontFamily.SYSTEM
    PresetFontFamily.SERIF         -> VaultReaderFontFamily.SERIF
    PresetFontFamily.SANS_SERIF    -> VaultReaderFontFamily.SANS_SERIF
    PresetFontFamily.MONOSPACE     -> VaultReaderFontFamily.MONOSPACE
    PresetFontFamily.OPEN_DYSLEXIC -> VaultReaderFontFamily.OPEN_DYSLEXIC
}
