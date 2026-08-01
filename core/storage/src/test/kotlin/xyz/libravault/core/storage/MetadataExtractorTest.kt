package xyz.libravault.core.storage

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Covers [MetadataExtractor.extractMarkdownTitle] — the title-precedence rule from
 * the Markdown viewer PRD: first `# H1`, else YAML front matter's `title:` field,
 * else null (caller falls back to filename). Pure string logic, no Android/SAF
 * dependency, so it's tested directly against an instance with mocked collaborators.
 */
class MetadataExtractorTest {

    private val extractor = MetadataExtractor(
        context       = mockk(relaxed = true),
        coverArtCache = mockk(relaxed = true),
        logger        = mockk(relaxed = true),
    )

    @Test
    fun `extracts title from leading H1`() {
        val text = "# My Document\n\nSome body text."
        assertEquals("My Document", extractor.extractMarkdownTitle(text))
    }

    @Test
    fun `H1 takes precedence over front matter title`() {
        val text = """
            ---
            title: Front Matter Title
            ---
            # Real Title

            Body.
        """.trimIndent()
        assertEquals("Real Title", extractor.extractMarkdownTitle(text))
    }

    @Test
    fun `falls back to front matter title when there is no H1`() {
        val text = """
            ---
            title: Front Matter Title
            ---
            Just a paragraph, no heading.
        """.trimIndent()
        assertEquals("Front Matter Title", extractor.extractMarkdownTitle(text))
    }

    @Test
    fun `front matter title tolerates quotes`() {
        val text = """
            ---
            title: "Quoted Title"
            ---
            Body.
        """.trimIndent()
        assertEquals("Quoted Title", extractor.extractMarkdownTitle(text))
    }

    @Test
    fun `returns null when there is neither H1 nor front matter title`() {
        val text = "Just a paragraph with no heading or front matter."
        assertNull(extractor.extractMarkdownTitle(text))
    }

    @Test
    fun `unterminated front matter block is not parsed as front matter`() {
        // No closing `---` found: the whole text (including the leading `---`
        // line) is treated as plain body. That leading line isn't a valid H1,
        // so this falls back to null (caller falls back to filename) rather
        // than reaching into the malformed block for a heading.
        val text = "---\ntitle: Never Closed\n\n# Heading Found In Body"
        assertNull(extractor.extractMarkdownTitle(text))
    }

    @Test
    fun `blank document returns null`() {
        assertNull(extractor.extractMarkdownTitle(""))
    }
}
