package xyz.libravault.core.ui.theme

/**
 * Font family choice bundled into a [ReadingPreset]. Mirrors `feature:reader`'s
 * `FontFamily` / `feature:vault`'s `VaultReaderFontFamily` one-for-one, but is
 * its own type rather than a reuse of either: those two are deliberately kept
 * as private-ish per-feature duplicates (see `VaultReaderSettings.kt`) so
 * neither feature module depends on the other, whereas [ReadingPreset] needs
 * exactly one shared home — core:ui, which both feature modules already
 * depend on for [ReadingTheme] — rather than two copies that could diverge.
 * Each feature maps its own font-family enum to/from this one at the call site.
 *
 * OPEN_DYSLEXIC added by #423 alongside the "Easy Read" built-in preset below.
 */
enum class PresetFontFamily { SYSTEM, SERIF, SANS_SERIF, MONOSPACE, OPEN_DYSLEXIC }

/**
 * A curated one-tap combination of [theme] + [fontFamily] + [fontSize] +
 * [lineSpacing] (#419) — the layer above the reader settings sheets' existing
 * granular sliders/chips, not a replacement for them.
 *
 * There is deliberately no "currently selected preset" stored anywhere:
 * picking a preset just sets all four underlying fields at once, and callers
 * derive the active preset (or "Custom", meaning none) by comparing the
 * current settings against [ReadingPresets.builtIns] via [matching]. That
 * keeps a manual slider tweak automatically falling back to "Custom" for
 * free, with no separate state that could drift out of sync with the actual
 * values.
 *
 * Deliberately flat and growable: margins/warmth (explicitly out of scope for
 * #419, called out in the issue as later additions) are additional fields
 * here later, not a new type, so today's built-in presets don't need to be
 * redefined when they land.
 */
data class ReadingPreset(
    val id: String,
    val label: String,
    val theme: ReadingTheme,
    val fontFamily: PresetFontFamily,
    val fontSize: Float,
    val lineSpacing: Float,
)

/**
 * Built-in presets (#419), named for LibraVault's own warm-leather/parchment
 * brand (see `Color.kt`) rather than reusing Apple Books' "Original/Quiet/
 * Paper/Bold/Calm/Focus" naming or grid layout — an explicit constraint from
 * the issue's design review: the *idea* of curated one-tap combos is worth
 * borrowing, the visual language is not.
 */
object ReadingPresets {
    val builtIns: List<ReadingPreset> = listOf(
        ReadingPreset(
            id          = "fireside",
            label       = "Fireside",
            theme       = ReadingTheme.DARK,
            fontFamily  = PresetFontFamily.SERIF,
            fontSize    = 1.0f,
            lineSpacing = 1.4f,
        ),
        ReadingPreset(
            id          = "parchment",
            label       = "Parchment",
            theme       = ReadingTheme.SEPIA,
            fontFamily  = PresetFontFamily.SERIF,
            fontSize    = 1.1f,
            lineSpacing = 1.5f,
        ),
        ReadingPreset(
            id          = "daylight",
            label       = "Daylight",
            theme       = ReadingTheme.LIGHT,
            fontFamily  = PresetFontFamily.SANS_SERIF,
            fontSize    = 1.0f,
            lineSpacing = 1.3f,
        ),
        ReadingPreset(
            id          = "long_read",
            label       = "Long Read",
            theme       = ReadingTheme.SYSTEM,
            fontFamily  = PresetFontFamily.SYSTEM,
            fontSize    = 1.2f,
            lineSpacing = 1.6f,
        ),
        // #423 — dyslexia-friendly typeface + generous line spacing bundled
        // together, per that issue's accessibility brief. Sepia background
        // (not Light/Dark) follows the same "reduce harsh contrast" guidance
        // as the typeface/spacing choice itself, and doubles as this app's
        // own parchment branding rather than a stark white page.
        ReadingPreset(
            id          = "easy_read",
            label       = "Easy Read",
            theme       = ReadingTheme.SEPIA,
            fontFamily  = PresetFontFamily.OPEN_DYSLEXIC,
            fontSize    = 1.1f,
            lineSpacing = 1.8f,
        ),
    )
}

/**
 * The built-in preset matching all four bundled fields exactly, or null if
 * the current combination doesn't match any of them ("Custom").
 */
fun List<ReadingPreset>.matching(
    theme: ReadingTheme,
    fontFamily: PresetFontFamily,
    fontSize: Float,
    lineSpacing: Float,
): ReadingPreset? = find {
    it.theme == theme &&
        it.fontFamily == fontFamily &&
        it.fontSize == fontSize &&
        it.lineSpacing == lineSpacing
}
