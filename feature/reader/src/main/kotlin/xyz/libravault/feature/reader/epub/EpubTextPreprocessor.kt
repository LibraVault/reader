package xyz.libravault.feature.reader.epub

/**
 * Cleans plain text extracted from EPUB HTML before it is passed to TTS,
 * removing typographic artifacts that would be read aloud unnecessarily,
 * and expanding abbreviations so the TTS engine pronounces them correctly.
 *
 * Patterns handled:
 *  - Soft hyphens (U+00AD) and hard-hyphenated line breaks
 *  - Standalone and labelled page numbers ("Page 42", "p. 42", "-- 42 --")
 *  - Roman-numeral page numbers on their own line (i, iv, xii, ...)
 *  - Footnote reference markers ([1], [23], superscript digits)
 *  - Decorative section separators (* * *, --, diamond, - - -, o o o, ...)
 *  - Figure / image / table captions ("Figure 1:", "Fig. 3", "Table 2:")
 *  - Short all-caps lines (<=6 words) -- running headers baked into chapter HTML
 *  - Unicode typographic characters -> TTS-friendly equivalents
 *  - Common abbreviation expansion (Dr., Mr., Mrs., e.g., i.e., etc.)
 *
 * Intentionally NOT handled here:
 *  - HTML tags, entities, script/style blocks -- already stripped upstream
 *  - Footnote body text -- indistinguishable from numbered lists without layout info
 *
 * All processing is local (pure regex / string ops) -- no network calls, no ML.
 */
internal object EpubTextPreprocessor {

    fun clean(text: String): String = text
        .removeSoftHyphens()
        .joinHyphenatedLineBreaks()
        .normalizeUnicodeChars()
        .removeDecorativeSeparators()
        .removeStandalonePageNumbers()
        .removeRomanNumeralPageNumbers()
        .removeLabelledPageNumbers()
        .removeFootnoteMarkers()
        .removeFigureCaptions()
        .removeRunningHeaders()
        .expandAbbreviations()
        .normalizeWhitespace()

    // ---- Character-level fixes ----------------------------------------------

    // U+00AD soft hyphens -- invisible but read aloud as "dash"
    private fun String.removeSoftHyphens() = replace("­", "")

    // "some-\nwhere" -> "somewhere"  (hard line-break hyphenation in older EPUBs)
    private fun String.joinHyphenatedLineBreaks() =
        replace(Regex("(\\w)-\\n(\\w)")) { mr -> mr.groupValues[1] + mr.groupValues[2] }

    // Replace common Unicode typographic chars with TTS-friendly equivalents.
    // Em-dash/en-dash -> prose equivalents so the TTS engine pauses naturally.
    // Curly quotes -> ASCII so abbreviation patterns don't need both styles.
    private fun String.normalizeUnicodeChars() = this
        .replace("’", "'")   // RIGHT SINGLE QUOTATION MARK
        .replace("‘", "'")   // LEFT SINGLE QUOTATION MARK
        .replace("“", "\"")  // LEFT DOUBLE QUOTATION MARK
        .replace("”", "\"")  // RIGHT DOUBLE QUOTATION MARK
        .replace("—", ", ")  // EM DASH -> ", "
        .replace("–", " to ") // EN DASH -> " to "
        .replace("…", "...")  // HORIZONTAL ELLIPSIS -> three dots
        .replace("·", " ")   // MIDDLE DOT (decorative bullet in some EPUBs)

    // ---- Structural noise removal -------------------------------------------

    // Decorative scene-break separators: lines whose entire non-whitespace
    // content is symbols, repeated dashes, asterisks, or Unicode ornaments.
    // Examples: "* * *", "-- -- --", diamond, "o o o", "- - -", "***", etc.
    private val decorativeSeparatorRe = Regex(
        """(?m)^\s*[-*~=_—–◆◇○●•†‡§¶×✦✧❖✿❧☙♦♠♣♥＊＃\s]{1,40}\s*$"""
    )
    private fun String.removeDecorativeSeparators() =
        replace(decorativeSeparatorRe, "\n")

    // Bare integer lines 1-4 digits -> page numbers inserted by EPUB generators
    private fun String.removeStandalonePageNumbers() =
        replace(Regex("""(?m)^\s*\d{1,4}\s*$"""), "")

    // Standalone roman numerals on their own line (front-matter / back-matter pages).
    // Matches i, ii, iii ... xix, xx, ... up to 20 characters to avoid false positives.
    private val romanNumeralLineRe = Regex(
        """(?mi)^\s*m{0,4}(?:cm|cd|d?c{0,3})(?:xc|xl|l?x{0,3})(?:ix|iv|v?i{0,3})\s*$"""
    )
    private fun String.removeRomanNumeralPageNumbers() =
        replace(romanNumeralLineRe) { mr ->
            // Only strip if the match is non-empty (exclude the all-empty case)
            if (mr.value.isBlank()) mr.value else ""
        }

    // "Page 42", "page 42", "p. 42", "pp. 12-14"
    private fun String.removeLabelledPageNumbers() = this
        .replace(Regex("""(?i)\bpp?\.?\s*\d+(?:\s*[–\-]\s*\d+)?\b"""), "")
        .replace(Regex("""(?i)\bpage\s+\d+\b"""), "")
        .replace(Regex("""-\s*\d+\s*-"""), "")

    // Inline footnote refs: [1], [23], (1), (23), superscript Unicode digits
    private fun String.removeFootnoteMarkers() = this
        .replace(Regex("""\[\d+]"""), "")
        .replace(Regex("""\(\d{1,3}\)"""), "")
        .replace(Regex("[¹²³⁴⁵⁶⁷⁸⁹⁰]+"), "")

    // Figure / image / table captions that would be read as garbled metadata.
    private val figureCaptionRe = Regex(
        """(?mi)^\s*(?:fig(?:ure)?|illustration|photo(?:graph)?|image|table|plate|chart|graph|map)\s*\.?\s*\d+\s*[.:\-]?[^\n]{0,120}$"""
    )
    private fun String.removeFigureCaptions() =
        replace(figureCaptionRe, "")

    // Short all-caps lines (<=6 space-separated tokens) are typically running
    // chapter/section headers baked into chapter HTML by poorly-structured EPUBs.
    // Requires at least 2 chars per token to avoid stripping single-letter words.
    private val runningHeaderRe = Regex(
        """(?m)^\s*(?:[A-Z]{2,}(?:\s+[A-Z]{2,}){0,5})\s*$"""
    )
    private fun String.removeRunningHeaders() =
        replace(runningHeaderRe, "")

    // ---- Abbreviation expansion ---------------------------------------------

    // Expand common abbreviations so the TTS engine reads them naturally.
    // Order matters: longer / more-specific patterns first.
    private fun String.expandAbbreviations() = this
        // Titles
        .replace(Regex("""\bMrs\."""), "Missus")
        .replace(Regex("""\bMr\."""), "Mister")
        .replace(Regex("""\bMs\."""), "Miz")
        .replace(Regex("""\bDr\."""), "Doctor")
        .replace(Regex("""\bProf\."""), "Professor")
        .replace(Regex("""\bSt\."""), "Saint")
        .replace(Regex("""\bRev\."""), "Reverend")
        .replace(Regex("""\bGen\."""), "General")
        .replace(Regex("""\bSgt\."""), "Sergeant")
        .replace(Regex("""\bCpt\."""), "Captain")
        .replace(Regex("""\bLt\."""), "Lieutenant")
        .replace(Regex("""\bCol\."""), "Colonel")
        // Connectives and qualifiers
        .replace(Regex("""\be\.g\."""), "for example")
        .replace(Regex("""\bi\.e\."""), "that is")
        .replace(Regex("""\betc\."""), "etcetera")
        .replace(Regex("""\bvs\."""), "versus")
        .replace(Regex("""\bviz\."""), "namely")
        .replace(Regex("""\bcf\."""), "compare")
        .replace(Regex("""\bapprox\."""), "approximately")
        .replace(Regex("""\bno\."""), "number")
        // Bibliographic
        .replace(Regex("""\bed\."""), "edition")
        .replace(Regex("""\bvol\."""), "volume")
        .replace(Regex("""\bch\."""), "chapter")
        .replace(Regex("""\bsec\."""), "section")

    // ---- Final whitespace normalisation -------------------------------------

    private fun String.normalizeWhitespace() = this
        .replace(Regex("""[ \t]+"""), " ")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
}
