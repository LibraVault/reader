package xyz.libravault.core.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeneratedCoverDeterminismTest {

    @Test
    fun `same title yields same palette index across calls`() {
        val a = paletteIndexFor("The Pragmatic Programmer")
        val b = paletteIndexFor("The Pragmatic Programmer")
        assertEquals(a, b)
    }

    @Test
    fun `palette index is within bounds`() {
        for (title in listOf("", "a", "Hello", "The Lord of the Rings", "1984", "Sapiens: A Brief History of Humankind")) {
            val idx = paletteIndexFor(title)
            assertTrue(idx in CoverPalette.indices, "index $idx out of bounds for title '$title'")
        }
    }

    @Test
    fun `initials extract two uppercase letters from multi-word titles`() {
        assertEquals("TP", initialsFor("The Pragmatic Programmer"))
        assertEquals("HH", initialsFor("homo homini"))
        assertEquals("SA", initialsFor("Sapiens: A Brief History of Humankind"))
    }

    @Test
    fun `initials extract two letters from single-word titles`() {
        assertEquals("DU", initialsFor("Dune"))
        assertEquals("BE", initialsFor("Be"))
        assertEquals("?", initialsFor(""))
    }

    @Test
    fun `initials are uppercase`() {
        assertEquals(initialsFor("hello world"), "HW")
    }
}