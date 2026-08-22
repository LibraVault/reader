package xyz.libravault.feature.vault

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.libravault.core.ui.theme.PresetFontFamily

/** Plain-JVM coverage for the [VaultReaderFontFamily] <-> [PresetFontFamily] mapping (#419). */
class FontFamilyPresetMappingTest {

    @Test
    fun `every VaultReaderFontFamily round-trips through PresetFontFamily unchanged`() {
        for (family in VaultReaderFontFamily.entries) {
            assertEquals(family, family.toPresetFontFamily().toVaultReaderFontFamily())
        }
    }

    @Test
    fun `every PresetFontFamily round-trips through VaultReaderFontFamily unchanged`() {
        for (family in PresetFontFamily.entries) {
            assertEquals(family, family.toVaultReaderFontFamily().toPresetFontFamily())
        }
    }
}
