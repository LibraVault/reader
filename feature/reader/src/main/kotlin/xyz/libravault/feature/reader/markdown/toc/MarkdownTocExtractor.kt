package xyz.libravault.feature.reader.markdown.toc

/** One heading found in a document, plus the index of the [MarkdownSection] it starts. */
data class TocEntry(
    val level: Int,
    val title: String,
    val sectionIndex: Int,
)

/**
 * A contiguous chunk of the source document: an optional heading line (null only for
 * a "preamble" section — text before the first heading, if any) followed by its body,
 * up to the next heading or end of document. Rendering the document as one
 * [MarkdownSection] per [text] value (instead of one giant blob) is what makes
 * per-heading scroll position tracking possible — see MarkdownReaderScreen's use of
 * `onGloballyPositioned` per section.
 */
data class MarkdownSection(
    val heading: TocEntry?,
    val text: String,
)

/**
 * Splits raw Markdown source into a table of contents plus the sections it indexes.
 *
 * Deliberately a standalone line scanner rather than sharing the renderer's internal
 * AST — mikepenz's simple `Markdown(content: String)` overload (the version this
 * project is pinned to, 0.28.0, for Kotlin 2.0 compatibility) doesn't expose its
 * parsed tree back to the caller. ATX headers are structurally simple enough that a
 * scanner is the pragmatic choice; the one real hazard is a line starting with `#`
 * inside a fenced code block (a shell comment, a Python comment) — tracked below so
 * those are never mistaken for headings.
 */
object MarkdownTocExtractor {

    private val headingPattern = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val fencePattern = Regex("""^(`{3,}|~{3,})""")

    private data class RawHeading(val level: Int, val title: String)

    fun extractSections(source: String): List<MarkdownSection> {
        // Raw (heading, body) pairs first — sectionIndex is only assigned afterward,
        // based on each entry's actual position in the final list. Assigning it
        // during the scan itself (e.g. "number of headings seen so far") would drift
        // out of sync with the real list index the moment a non-empty preamble (or
        // any other headingless section) sits before a heading.
        val raw = mutableListOf<Pair<RawHeading?, StringBuilder>>()
        var inFence = false

        fun currentBody(): StringBuilder? = raw.lastOrNull()?.second

        for (line in source.lineSequence()) {
            if (fencePattern.containsMatchIn(line.trimStart())) {
                inFence = !inFence
                val body = currentBody() ?: StringBuilder().also { raw += null to it }
                body.appendLine(line)
                continue
            }

            val headingMatch = if (!inFence) headingPattern.find(line) else null
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                val title = headingMatch.groupValues[2].trim()
                raw += RawHeading(level, title) to StringBuilder().apply { appendLine(line) }
                continue
            }

            val body = currentBody()
            when {
                body != null -> body.appendLine(line)
                line.isNotBlank() -> raw += null to StringBuilder().appendLine(line)
                else -> Unit // leading blank lines before any real content — drop
            }
        }

        return raw
            .filter { (heading, body) -> heading != null || body.isNotBlank() }
            .mapIndexed { index, (heading, body) ->
                MarkdownSection(
                    heading = heading?.let { TocEntry(it.level, it.title, index) },
                    text = body.toString(),
                )
            }
    }

    fun extractToc(source: String): List<TocEntry> =
        extractSections(source).mapNotNull { it.heading }
}
