package xyz.libravault.feature.reader.markdown

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [MarkdownTtsTextExtractor] — the Markdown-syntax-stripping half of
 * #124's text-extraction layer. See that object's own doc comment for why this is not
 * (yet) wired into a real playback path on Android, unlike its iOS counterpart in the
 * same change.
 */
class MarkdownTtsTextExtractorTest {

    @Test
    fun `one chapter per heading section`() {
        val chapters = MarkdownTtsTextExtractor.chaptersForNarration("# One\nFirst body.\n# Two\nSecond body.")

        assertEquals(listOf("One", "Two"), chapters.map { it.title })
        assertEquals("One\nFirst body.", chapters[0].text)
        assertEquals("Two\nSecond body.", chapters[1].text)
    }

    @Test
    fun `a headingless document becomes one Untitled chapter`() {
        val chapters = MarkdownTtsTextExtractor.chaptersForNarration("Just a paragraph, no heading at all.")

        assertEquals(listOf("Untitled"), chapters.map { it.title })
        assertEquals("Just a paragraph, no heading at all.", chapters.first().text)
    }

    @Test
    fun `an empty document produces no chapters`() {
        assertTrue(MarkdownTtsTextExtractor.chaptersForNarration("").isEmpty())
    }

    @Test
    fun `code-only, table-only, and thematic-break-only content produces no chapters`() {
        // Deliberately headingless — a heading's own title text is legitimately
        // speakable content (see the "heading markers are stripped" test below), so a
        // document that HAS a heading always produces at least one chapter even if
        // everything else in it is unspeakable. This is the Markdown-specific edge
        // case that needs a real playback-side guard once this is wired up — an
        // image-only or code-only document parses fine but has nothing speakable,
        // unlike a near-empty EPUB/PDF, which isn't realistic.
        val source = "```\nsome code\n```\n\n---\n\n| A | B |\n|---|---|\n| 1 | 2 |"
        assertTrue(MarkdownTtsTextExtractor.chaptersForNarration(source).isEmpty())
    }

    @Test
    fun `heading markers are stripped but the title text is spoken`() {
        val chapters = MarkdownTtsTextExtractor.chaptersForNarration("###### Deep Heading\nBody.")
        assertTrue(chapters.first().text.startsWith("Deep Heading"))
        assertTrue(!chapters.first().text.contains("#"))
    }

    @Test
    fun `bold, italic, and inline code markers are stripped to plain text`() {
        val cleaned = MarkdownTtsTextExtractor.stripMarkdownSyntax("Some **bold** and *italic* and `code`.")
        assertEquals("Some bold and italic and code.", cleaned)
    }

    @Test
    fun `bold-italic combined markers are stripped`() {
        val cleaned = MarkdownTtsTextExtractor.stripMarkdownSyntax("***very important***")
        assertEquals("very important", cleaned)
    }

    @Test
    fun `list markers are stripped but item text is kept`() {
        val cleaned = MarkdownTtsTextExtractor.stripMarkdownSyntax("- First\n- Second\n3. Third")
        assertEquals("First\nSecond\nThird", cleaned)
    }

    @Test
    fun `block quote markers are stripped but quoted text is kept`() {
        val cleaned = MarkdownTtsTextExtractor.stripMarkdownSyntax("> Wise words here.")
        assertEquals("Wise words here.", cleaned)
    }

    @Test
    fun `image alt text is spoken, the reference itself is not`() {
        val cleaned = MarkdownTtsTextExtractor.stripMarkdownSyntax("![A sunset over the ocean](./sunset.png)")
        assertEquals("A sunset over the ocean", cleaned)
    }

    @Test
    fun `an image with no alt text contributes nothing`() {
        val cleaned = MarkdownTtsTextExtractor.stripMarkdownSyntax("![](./sunset.png)")
        assertEquals("", cleaned)
    }

    @Test
    fun `link text is spoken, the URL is not`() {
        val cleaned = MarkdownTtsTextExtractor.stripMarkdownSyntax("See [the docs](https://example.com) for details.")
        assertEquals("See the docs for details.", cleaned)
    }

    @Test
    fun `fenced code blocks are removed entirely, including the fence markers`() {
        val cleaned = MarkdownTtsTextExtractor.stripMarkdownSyntax("Before.\n```kotlin\nval x = 1\n```\nAfter.")
        assertEquals("Before.\nAfter.", cleaned)
    }

    @Test
    fun `a thematic break line is removed entirely`() {
        val cleaned = MarkdownTtsTextExtractor.stripMarkdownSyntax("Above.\n\n---\n\nBelow.")
        assertEquals("Above.\n\nBelow.", cleaned)
    }

    @Test
    fun `table rows and the separator row are removed entirely`() {
        val cleaned = MarkdownTtsTextExtractor.stripMarkdownSyntax("| A | B |\n|---|---|\n| 1 | 2 |")
        assertEquals("", cleaned)
    }
}
