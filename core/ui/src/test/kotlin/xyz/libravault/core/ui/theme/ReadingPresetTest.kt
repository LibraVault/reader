package xyz.libravault.core.ui.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Plain-JVM coverage for [ReadingPreset]/[ReadingPresets]/[matching] (#419). */
class ReadingPresetTest {

    @Test
    fun `there are between 3 and 4 built-in presets`() {
        assertTrue(ReadingPresets.builtIns.size in 3..4)
    }

    @Test
    fun `built-in preset ids are unique`() {
        val ids = ReadingPresets.builtIns.map { it.id }
        assertEquals(ids.distinct(), ids)
    }

    @Test
    fun `built-in preset labels are unique`() {
        val labels = ReadingPresets.builtIns.map { it.label }
        assertEquals(labels.distinct(), labels)
    }

    @Test
    fun `built-in preset names do not reuse Apple Books naming`() {
        // Explicit design-review constraint from #419: the idea of curated
        // one-tap combos is worth borrowing, Apple's own naming is not.
        val appleNames = setOf("original", "quiet", "paper", "bold", "calm", "focus")
        ReadingPresets.builtIns.forEach { preset ->
            assertTrue(
                preset.label.lowercase() !in appleNames,
                "${preset.label} reuses an Apple Books preset name",
            )
        }
    }

    @Test
    fun `matching finds the exact preset for its own bundled values`() {
        ReadingPresets.builtIns.forEach { preset ->
            val found = ReadingPresets.builtIns.matching(
                theme       = preset.theme,
                fontFamily  = preset.fontFamily,
                fontSize    = preset.fontSize,
                lineSpacing = preset.lineSpacing,
            )
            assertEquals(preset, found)
        }
    }

    @Test
    fun `matching returns null when only some fields match a preset`() {
        val fireside = ReadingPresets.builtIns.first { it.id == "fireside" }
        val found = ReadingPresets.builtIns.matching(
            theme       = fireside.theme,
            fontFamily  = fireside.fontFamily,
            fontSize    = fireside.fontSize,
            // Deliberately mismatched — a manual slider tweak shouldn't still
            // read back as this preset.
            lineSpacing = fireside.lineSpacing + 0.3f,
        )
        assertNull(found)
    }

    @Test
    fun `matching returns null for a combination no preset defines`() {
        val found = ReadingPresets.builtIns.matching(
            theme       = ReadingTheme.DARK,
            fontFamily  = PresetFontFamily.MONOSPACE,
            fontSize    = 1.7f,
            lineSpacing = 2.5f,
        )
        assertNull(found)
    }
}
