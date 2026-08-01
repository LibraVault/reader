package xyz.libravault.feature.reader.markdown.toc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkdownTocExtractorTest {

    @Test
    fun `empty document has no sections or toc entries`() {
        assertEquals(emptyList<MarkdownSection>(), MarkdownTocExtractor.extractSections(""))
        assertEquals(emptyList<TocEntry>(), MarkdownTocExtractor.extractToc(""))
    }

    @Test
    fun `single heading with body`() {
        val toc = MarkdownTocExtractor.extractToc("# Title\nSome body text.")
        assertEquals(listOf(TocEntry(level = 1, title = "Title", sectionIndex = 0)), toc)
    }

    @Test
    fun `each toc entry's sectionIndex matches its actual position in extractSections`() {
        val source = "# One\nbody one\n## Two\nbody two\n### Three\nbody three"
        val sections = MarkdownTocExtractor.extractSections(source)
        val toc = MarkdownTocExtractor.extractToc(source)

        assertEquals(3, sections.size)
        toc.forEach { entry ->
            assertEquals(entry, sections[entry.sectionIndex].heading, "sectionIndex ${entry.sectionIndex} should point back at this exact heading")
        }
    }

    @Test
    fun `non-empty preamble before the first heading does not desync sectionIndex`() {
        // Regression case for a real bug found during implementation: assigning
        // sectionIndex purely from "how many headings seen so far" drifts out of
        // sync with the real list position the moment a preamble section exists,
        // since the preamble itself occupies section index 0.
        val source = "Intro paragraph before any heading.\n# H1\nbody"
        val sections = MarkdownTocExtractor.extractSections(source)

        assertEquals(2, sections.size)
        assertEquals(null, sections[0].heading)
        assertTrue(sections[0].text.contains("Intro paragraph"))

        val toc = MarkdownTocExtractor.extractToc(source)
        assertEquals(listOf(TocEntry(level = 1, title = "H1", sectionIndex = 1)), toc)
        assertEquals(toc[0], sections[1].heading)
    }

    @Test
    fun `blank preamble before the first heading is dropped, not an empty section`() {
        val source = "\n\n# H1\nbody"
        val sections = MarkdownTocExtractor.extractSections(source)

        assertEquals(1, sections.size)
        assertEquals(TocEntry(level = 1, title = "H1", sectionIndex = 0), sections[0].heading)
    }

    @Test
    fun `adjacent headings with no body between them`() {
        val toc = MarkdownTocExtractor.extractToc("# H1\n## H2\nbody")
        assertEquals(
            listOf(
                TocEntry(level = 1, title = "H1", sectionIndex = 0),
                TocEntry(level = 2, title = "H2", sectionIndex = 1),
            ),
            toc,
        )
    }

    @Test
    fun `hash inside a fenced code block is not treated as a heading`() {
        val source = "```\n# not a heading\n```\n# Real Heading\nbody"
        val toc = MarkdownTocExtractor.extractToc(source)

        assertEquals(listOf(TocEntry(level = 1, title = "Real Heading", sectionIndex = 1)), toc)
    }

    @Test
    fun `tilde fences are also respected`() {
        val source = "~~~\n# not a heading\n~~~\n# Real Heading"
        val toc = MarkdownTocExtractor.extractToc(source)

        assertEquals(listOf(TocEntry(level = 1, title = "Real Heading", sectionIndex = 1)), toc)
    }

    @Test
    fun `heading requires a space after the hashes`() {
        val toc = MarkdownTocExtractor.extractToc("#NotAHeading\nJust text.")
        assertTrue(toc.isEmpty())
    }

    @Test
    fun `trailing decorative hashes are stripped from the title`() {
        val toc = MarkdownTocExtractor.extractToc("## Subtitle ##")
        assertEquals("Subtitle", toc.single().title)
    }

    @Test
    fun `all six heading levels are recognized`() {
        val source = (1..6).joinToString("\n") { "#".repeat(it) + " H$it" }
        val toc = MarkdownTocExtractor.extractToc(source)

        assertEquals((1..6).toList(), toc.map { it.level })
    }

    @Test
    fun `document with no headings produces a single headingless section`() {
        val sections = MarkdownTocExtractor.extractSections("Just a paragraph, no headings at all.")

        assertEquals(1, sections.size)
        assertEquals(null, sections[0].heading)
        assertTrue(MarkdownTocExtractor.extractToc("Just a paragraph, no headings at all.").isEmpty())
    }
}
