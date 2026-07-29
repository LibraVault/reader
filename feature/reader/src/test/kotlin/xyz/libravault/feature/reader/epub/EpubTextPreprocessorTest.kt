package xyz.libravault.feature.reader.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [EpubTextPreprocessor], the regex-chain that cleans EPUB
 * text before it's handed to TTS. Pure string transforms with no Android
 * dependency, so worth pinning precisely — a regex ordering change here is
 * easy to get subtly wrong and would only surface as garbled narration.
 */
class EpubTextPreprocessorTest {

    // ── Soft hyphens / hyphenated line breaks ────────────────────────────────

    @Test
    fun `removes soft hyphens`() {
        val result = EpubTextPreprocessor.clean("un­believable")
        assertEquals("unbelievable", result)
    }

    @Test
    fun `joins hard-hyphenated line breaks`() {
        val result = EpubTextPreprocessor.clean("some-\nwhere over the rainbow")
        assertTrue(result.contains("somewhere"), "expected joined word, got: $result")
    }

    // ── Unicode normalization ────────────────────────────────────────────────

    @Test
    fun `normalizes curly quotes to ascii`() {
        val result = EpubTextPreprocessor.clean("‘Hello’ she said, “welcome”")
        assertEquals("'Hello' she said, \"welcome\"", result)
    }

    @Test
    fun `normalizes em dash and en dash`() {
        val result = EpubTextPreprocessor.clean("wait—what")
        assertTrue(result.contains(", "), "em dash should become a comma pause, got: $result")

        val enDash = EpubTextPreprocessor.clean("pages 10–20")
        assertTrue(enDash.contains(" to ") || !enDash.contains('–'), "en dash should be spelled out, got: $enDash")
    }

    @Test
    fun `normalizes ellipsis`() {
        val result = EpubTextPreprocessor.clean("wait… what")
        assertTrue(result.contains("..."), "expected three dots, got: $result")
    }

    // ── Decorative separators ────────────────────────────────────────────────

    @Test
    fun `removes decorative scene break separators`() {
        val result = EpubTextPreprocessor.clean("End of chapter.\n* * *\nNext chapter begins.")
        assertFalse(result.contains("*"), "decorative asterisks should be stripped, got: $result")
    }

    // ── Page numbers ──────────────────────────────────────────────────────────

    @Test
    fun `removes standalone page number lines`() {
        val result = EpubTextPreprocessor.clean("End of the page.\n42\nStart of next page.")
        assertFalse(result.contains("\n42\n") || result.trim() == "42", "bare page number should be removed, got: $result")
    }

    @Test
    fun `removes labelled page numbers`() {
        val result = EpubTextPreprocessor.clean("See page 42 for details.")
        assertFalse(result.contains("page 42"), "labelled page number should be removed, got: $result")
    }

    @Test
    fun `preserves normal prose that only coincidentally has digits`() {
        val result = EpubTextPreprocessor.clean("She turned twenty-one years old that summer.")
        assertEquals("She turned twenty-one years old that summer.", result)
    }

    // ── Footnote markers ──────────────────────────────────────────────────────

    @Test
    fun `removes bracketed footnote markers`() {
        val result = EpubTextPreprocessor.clean("This is a claim[1] worth citing.")
        assertFalse(result.contains("[1]"), "footnote marker should be removed, got: $result")
    }

    @Test
    fun `removes superscript footnote digits`() {
        val result = EpubTextPreprocessor.clean("This is a claim¹² worth citing.")
        assertFalse(result.any { it in "¹²³" }, "superscript digits should be removed, got: $result")
    }

    // ── Figure captions ───────────────────────────────────────────────────────

    @Test
    fun `removes figure captions on their own line`() {
        val result = EpubTextPreprocessor.clean("Some prose.\nFigure 1: A diagram of the system.\nMore prose.")
        assertFalse(result.contains("Figure 1"), "figure caption should be removed, got: $result")
    }

    // ── Running headers ───────────────────────────────────────────────────────

    @Test
    fun `removes short all-caps running headers`() {
        val result = EpubTextPreprocessor.clean("CHAPTER ONE\nIt was a dark and stormy night.")
        assertFalse(result.contains("CHAPTER ONE"), "running header should be removed, got: $result")
        assertTrue(result.contains("It was a dark and stormy night."))
    }

    @Test
    fun `preserves normal sentences that are not all-caps`() {
        val result = EpubTextPreprocessor.clean("It was a dark and stormy night.")
        assertEquals("It was a dark and stormy night.", result)
    }

    // ── Abbreviation expansion ────────────────────────────────────────────────

    @Test
    fun `expands common titles`() {
        val result = EpubTextPreprocessor.clean("Dr. Smith met Mrs. Jones and Mr. Lee.")
        assertEquals("Doctor Smith met Missus Jones and Mister Lee.", result)
    }

    @Test
    fun `expands e-g-i-e-and-etc`() {
        val result = EpubTextPreprocessor.clean("Bring supplies, e.g. water, i.e. the essentials, etc.")
        assertTrue(result.contains("for example"), "e.g. should expand, got: $result")
        assertTrue(result.contains("that is"), "i.e. should expand, got: $result")
        assertTrue(result.contains("etcetera"), "etc. should expand, got: $result")
    }

    // ── Whitespace normalization ─────────────────────────────────────────────

    @Test
    fun `collapses runs of spaces and tabs`() {
        val result = EpubTextPreprocessor.clean("too    many   spaces")
        assertEquals("too many spaces", result)
    }

    @Test
    fun `collapses three-or-more blank lines to a single blank line`() {
        val result = EpubTextPreprocessor.clean("First.\n\n\n\n\nSecond.")
        assertEquals("First.\n\nSecond.", result)
    }

    @Test
    fun `trims leading and trailing whitespace`() {
        val result = EpubTextPreprocessor.clean("   \n  padded text  \n   ")
        assertEquals("padded text", result)
    }

    // ── Idempotence / no-op on already-clean text ────────────────────────────

    @Test
    fun `does not mangle already-clean prose`() {
        val text = "It was the best of times, it was the worst of times."
        assertEquals(text, EpubTextPreprocessor.clean(text))
    }
}
