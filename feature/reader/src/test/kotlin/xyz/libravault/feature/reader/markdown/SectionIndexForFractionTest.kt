package xyz.libravault.feature.reader.markdown

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Pure-function coverage for the fraction→section-index conversion both the initial
 * scroll-position restore and bookmark-tap navigation in [MarkdownReaderScreen] depend
 * on (#125) — extracted specifically so this could be tested without a Compose host.
 */
class SectionIndexForFractionTest {

    @Test
    fun `null fraction has no target`() {
        assertNull(sectionIndexForFraction(null, sectionCount = 5))
    }

    @Test
    fun `zero sections has no target regardless of fraction`() {
        assertNull(sectionIndexForFraction(0.5, sectionCount = 0))
    }

    @Test
    fun `fraction 0 targets the first section`() {
        // Distinct from MarkdownReaderScreen's own restore-path guard, which treats
        // 0.0 as "nothing to restore" — this function itself must not special-case it,
        // since a bookmark saved at the very top of the document is still a real,
        // valid navigation target for the bookmark-tap path.
        assertEquals(0, sectionIndexForFraction(0.0, sectionCount = 4))
    }

    @Test
    fun `fraction 1 targets the last section, not one past it`() {
        assertEquals(3, sectionIndexForFraction(1.0, sectionCount = 4))
    }

    @Test
    fun `a fraction just under 1 does not round up past the last valid index`() {
        // 0.999999 * 4 rounds to 4, which is out of range for a 4-section document
        // (valid indices 0..3) — must clamp, not just round.
        assertEquals(3, sectionIndexForFraction(0.999999, sectionCount = 4))
    }

    @Test
    fun `a middling fraction picks the nearest section by rounding`() {
        assertEquals(2, sectionIndexForFraction(0.5, sectionCount = 4)) // 0.5*4=2.0 -> 2
        assertEquals(1, sectionIndexForFraction(0.3, sectionCount = 4)) // 0.3*4=1.2 -> 1
    }

    @Test
    fun `an out-of-range fraction clamps instead of returning an invalid index`() {
        // A pre-migration Markdown bookmark's raw pixel value (e.g. "scroll:4200")
        // parses as a valid but wildly out-of-range Double under the new fraction
        // interpretation. MIGRATION_6_7 resets those rows, but this clamp is a second
        // line of defense against ever computing an out-of-bounds index from one.
        assertEquals(3, sectionIndexForFraction(4200.0, sectionCount = 4))
        assertEquals(0, sectionIndexForFraction(-1.0, sectionCount = 4))
    }

    @Test
    fun `a single-section document always targets index 0`() {
        assertEquals(0, sectionIndexForFraction(0.0, sectionCount = 1))
        assertEquals(0, sectionIndexForFraction(0.5, sectionCount = 1))
        assertEquals(0, sectionIndexForFraction(1.0, sectionCount = 1))
    }
}
