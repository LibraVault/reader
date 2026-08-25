package xyz.libravault.feature.reader.markdown

import xyz.libravault.core.domain.model.ReaderChapter
import xyz.libravault.feature.reader.epub.EpubTextPreprocessor
import xyz.libravault.feature.reader.markdown.toc.MarkdownTocExtractor

/**
 * Read Aloud text extraction for Markdown (#124) — converts raw Markdown source into
 * narratable [ReaderChapter]s (#591 Phase 1), one per [MarkdownTocExtractor] section (the
 * same heading-delimited granularity iOS's `chaptersForNarration` uses, and the same one
 * MarkdownReaderScreen's own TOC/scroll-restore logic already splits the document by).
 * [ReaderChapter.index] is the chapter's position in the *returned* (narratable-only) list,
 * not its source section index — a blank/unspeakable section (code-only, table-only) is
 * dropped entirely rather than leaving a gap.
 *
 * Originally landed text-extraction only (#124/#136) — the Android EPUB Read Aloud
 * playback path this was written to give parity with didn't exist yet at the time. It
 * was wired into the reader's mini-bar in #276, once #137 built that playback path for
 * EPUB and gave this something real to plug into
 * ([xyz.libravault.feature.reader.markdown.MarkdownReaderViewModel.getChapterTextFromProgression]).
 *
 * Deliberately reuses [EpubTextPreprocessor] for the final prose-naturalization pass
 * (footnote markers, decorative separators, page numbers, abbreviation expansion) —
 * that logic doesn't care whether the plain text it receives originated from HTML or
 * Markdown, and `internal` visibility in Kotlin is module-scoped, not
 * package-scoped, so it's already reachable here without any visibility change.
 */
object MarkdownTtsTextExtractor {

    fun chaptersForNarration(source: String): List<ReaderChapter> {
        val sections = MarkdownTocExtractor.extractSections(source)
        return sections.mapNotNull { section ->
            val cleaned = EpubTextPreprocessor.clean(stripMarkdownSyntax(section.text))
            if (cleaned.isBlank()) null else (section.heading?.title ?: "Untitled") to cleaned
        }.mapIndexed { index, (title, cleaned) ->
            // Markdown's full text is already resolved synchronously above — unlike
            // EPUB/PDF, there's no per-chapter I/O to defer, so textProvider just wraps
            // the already-computed String (see ReaderChapter's doc comment on why the
            // type is still a suspend lambda).
            ReaderChapter(title = title, index = index, textProvider = { cleaned })
        }
    }

    // ── Markdown syntax stripping ────────────────────────────────────────────────
    //
    // Line-oriented, matching MarkdownTocExtractor's own approach (a standalone
    // scanner rather than sharing the renderer's internal AST — see that file's doc
    // comment for why the renderer doesn't expose one at the pinned version). Applied
    // to one section's raw text at a time, so a fenced code block spanning a section
    // boundary is not a concern (extractSections already resolves fence state itself).

    private val headingLine = Regex("""^#{1,6}\s+""")
    private val fenceLine = Regex("""^(`{3,}|~{3,})""")
    private val blockQuoteMarker = Regex("""^>\s?""")
    private val unorderedListMarker = Regex("""^\s*[-*+]\s+""")
    private val orderedListMarker = Regex("""^\s*\d+[.)]\s+""")
    private val thematicBreak = Regex("""^\s*([-*_])\s*(\1\s*){2,}$""")
    private val tableRow = Regex("""^\s*\|.*\|\s*$""")
    private val tableSeparator = Regex("""^\s*\|?[\s:|-]+\|?\s*$""")
    // ![alt](url) -> alt text, the same choice iOS's narrationText makes: alt text is
    // already written specifically to describe the image in words, unlike everything
    // else stripped below (which has nothing worth speaking at all).
    private val image = Regex("""!\[([^\]]*)]\([^)]*\)""")
    private val boldItalic = Regex("""\*\*\*([^*]+)\*\*\*|___([^_]+)___""")
    private val bold = Regex("""\*\*([^*]+)\*\*|__([^_]+)__""")
    private val italic = Regex("""\*([^*]+)\*|_([^_]+)_""")
    private val inlineCode = Regex("""`([^`]+)`""")
    private val link = Regex("""\[([^\]]*)]\([^)]*\)""")

    internal fun stripMarkdownSyntax(text: String): String {
        var inFence = false
        val lines = text.lineSequence().mapNotNull { rawLine ->
            if (fenceLine.containsMatchIn(rawLine.trimStart())) {
                inFence = !inFence
                return@mapNotNull null // the fence delimiter itself is never spoken
            }
            if (inFence) return@mapNotNull null // code block body — see class doc

            if (tableRow.matches(rawLine) || tableSeparator.matches(rawLine)) return@mapNotNull null
            if (thematicBreak.matches(rawLine.trim())) return@mapNotNull null

            var line = rawLine
            line = headingLine.replace(line, "")
            line = blockQuoteMarker.replace(line, "")
            line = unorderedListMarker.replace(line, "")
            line = orderedListMarker.replace(line, "")
            line = image.replace(line) { it.groupValues[1] }
            line = link.replace(line) { it.groupValues[1] }
            line = boldItalic.replace(line) { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            line = bold.replace(line) { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            line = italic.replace(line) { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            line = inlineCode.replace(line) { it.groupValues[1] }
            line
        }

        // A removed line (fence delimiter, table row, thematic break) leaves the blank
        // lines that surrounded it in the *source* — e.g. "Above.\n\n---\n\nBelow." has
        // its own blank line on each side of "---" — both still present once "---"
        // itself is filtered out. Collapsing runs of blank lines here, rather than
        // trying to special-case which removals should also swallow a neighboring
        // blank, keeps every removal rule above simple and gives TTS one clean pause
        // instead of a stutter of empty lines.
        val collapsed = mutableListOf<String>()
        for (line in lines) {
            if (line.isEmpty() && collapsed.lastOrNull()?.isEmpty() == true) continue
            collapsed.add(line)
        }
        return collapsed.joinToString("\n").trim()
    }
}
