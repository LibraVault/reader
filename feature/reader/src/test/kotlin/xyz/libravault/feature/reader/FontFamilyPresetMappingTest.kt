package xyz.libravault.feature.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.libravault.core.ui.theme.PresetFontFamily

/** Plain-JVM coverage for the [FontFamily] <-> [PresetFontFamily] mapping (#419). */
class FontFamilyPresetMappingTest {

    @Test
    fun `every FontFamily round-trips through PresetFontFamily unchanged`() {
        for (family in FontFamily.entries) {
            assertEquals(family, family.toPresetFontFamily().toFontFamily())
        }
    }

    @Test
    fun `every PresetFontFamily round-trips through FontFamily unchanged`() {
        for (family in PresetFontFamily.entries) {
            assertEquals(family, family.toFontFamily().toPresetFontFamily())
        }
    }
}
