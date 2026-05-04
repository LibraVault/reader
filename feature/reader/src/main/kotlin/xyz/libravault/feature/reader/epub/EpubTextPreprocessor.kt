package xyz.libravault.feature.reader.epub

/**
 * Cleans plain text extracted from EPUB HTML before it is passed to TTS,
 * removing typographic artifacts that would be read aloud unnecessarily.
 *
 * Patterns handled:
 *  - Standalone page numbers (bare integers on their own line, e.g. "123")
 *  - Labelled page numbers ("Page 123", "p. 123", "— 123 —")
 *  - Footnote reference markers ("[1]", "[23]", Unicode superscript digits)
 *  - Soft hyphens (U+00AD) inserted by EPUB generators
 *  - Hard-hyphenated line breaks (word-\nnext → wordnext)
 *
 * Intentionally NOT handled here (handled by stripHtml or left to TTS engine):
 *  - HTML tags, entities, script/style blocks — already stripped upstream
 *  - Running headers — require inter-chapter state; deferred to a future pass
 *  - Footnote body text — indistinguishable from numbered lists without layout info
 *
 * A regex preprocessor covers ~90 % of real-world EPUB artifacts with zero APK
 * overhead. A small local model (e.g. ONNX DistilBERT ~66 MB) could improve
 * recall for ambiguous cases (e.g. single-sentence paragraphs that are headers
 * vs. body text) at the cost of APK size and an inference pass per chapter.
 */
internal object EpubTextPreprocessor {

    fun clean(text: String): String = text
        .removeSoftHyphens()
        .joinHyphenatedLineBreaks()
        .removeStandalonePageNumbers()
        .removeLabelledPageNumbers()
        .removeFootnoteMarkers()
        .normalizeWhitespace()

    // U+00AD soft hyphens inserted by EPUB generators — invisible but read aloud as "dash"
    private fun String.removeSoftHyphens() = replace("­", "")

    // "some-\nwhere" → "somewhere"  (hard line-break hyphenation in older EPUBs)
    private fun String.joinHyphenatedLineBreaks() =
        replace(Regex("(\\w)-\\n(\\w)")) { mr -> mr.groupValues[1] + mr.groupValues[2] }

    // Lines that are only digits 1–4 chars long → page numbers
    // Anchored to line boundaries; \s* allows leading/trailing spaces on the line.
    private fun String.removeStandalonePageNumbers() =
        replace(Regex("(?m)^\\s*\\d{1,4}\\s*$"), "")

    // "Page 42", "page 42", "p. 42", "— 42 —", "pp. 12–14"
    private fun String.removeLabelledPageNumbers() =
        replace(Regex("(?i)\\bpp?\\.?\\s*\\d+(?:\\s*[–-]\\s*\\d+)?\\b"), "")
            .replace(Regex("(?i)\\bpage\\s+\\d+\\b"), "")
            .replace(Regex("—\\s*\\d+\\s*—"), "")

    // Inline footnote references: [1], [23], superscript digits ¹²³…
    private fun String.removeFootnoteMarkers() =
        replace(Regex("\\[\\d+]"), "")
            .replace(Regex("[¹²³⁴⁵⁶⁷⁸⁹⁰]+"), "")

    private fun String.normalizeWhitespace() =
        replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
}
