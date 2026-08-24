import Foundation

/// Cleans chapter text before it's handed to Read Aloud (either engine —
/// PocketTTS or the system `AVSpeechSynthesizer`), removing typographic
/// artifacts that would otherwise be read aloud, and expanding abbreviations
/// so narration sounds natural instead of spelling them out.
///
/// A deliberate, faithful Swift port of Android's
/// `feature/reader/.../epub/EpubTextPreprocessor.kt` — same regex chain, same
/// order, same patterns. Android already had this (added well before any
/// iOS equivalent existed); iOS had none, so every chapter's page numbers,
/// footnote markers, and running headers were being read aloud verbatim,
/// including PDF's, which Android's Read Aloud doesn't even support yet (see
/// `ReaderScreen.readAloudSupported` vs. `BookContentProvider.supportsChapterParsing`).
///
/// Patterns handled (see each private helper's own comment for the exact regex):
///  - Soft hyphens (U+00AD) and hard-hyphenated line breaks
///  - Standalone and labelled page numbers ("Page 42", "p. 42", "-- 42 --")
///  - Roman-numeral page numbers on their own line (i, iv, xii, ...)
///  - Footnote reference markers ([1], (23), superscript digits)
///  - Decorative section separators (* * *, --, diamond, - - -, o o o, ...)
///  - Figure / image / table captions ("Figure 1:", "Fig. 3", "Table 2:")
///  - Short all-caps lines (<=6 words) - running headers baked into chapter HTML
///  - Unicode typographic characters -> TTS-friendly equivalents
///  - Common abbreviation expansion (Dr., Mr., Mrs., e.g., i.e., etc.)
///
/// Intentionally NOT handled here:
///  - HTML/Markdown syntax - already stripped upstream by EPUBParser/
///    PDFParser/MarkdownDocumentParser before `AppState.chapterText(for:)`
///    calls this
///  - Footnote body text - indistinguishable from numbered lists without
///    layout info
///
/// All processing is local (pure regex/string ops) - no network calls, no ML.
/// Keep this in sync with `EpubTextPreprocessor.kt` by hand; there is no
/// shared implementation across the Kotlin/Swift boundary for pure string
/// logic like this.
enum TtsTextNormalizer {

    static func clean(_ text: String) -> String {
        var result = text
        result = removeSoftHyphens(result)
        result = joinHyphenatedLineBreaks(result)
        result = normalizeUnicodeChars(result)
        result = removeDecorativeSeparators(result)
        result = removeStandalonePageNumbers(result)
        result = removeRomanNumeralPageNumbers(result)
        result = removeLabelledPageNumbers(result)
        result = removeFootnoteMarkers(result)
        result = removeFigureCaptions(result)
        result = removeRunningHeaders(result)
        result = expandAbbreviations(result)
        result = normalizeWhitespace(result)
        return result
    }

    // MARK: - Character-level fixes

    // U+00AD soft hyphens - invisible but read aloud as "dash" by some engines.
    private static func removeSoftHyphens(_ text: String) -> String {
        text.replacingOccurrences(of: "\u{00AD}", with: "")
    }

    // "some-\nwhere" -> "somewhere" (hard line-break hyphenation in older EPUBs/PDFs)
    private static func joinHyphenatedLineBreaks(_ text: String) -> String {
        text.replacingOccurrences(
            of: #"(\w)-\n(\w)"#,
            with: "$1$2",
            options: .regularExpression
        )
    }

    // Replace common Unicode typographic chars with TTS-friendly equivalents.
    // Em-dash/en-dash -> prose equivalents so the TTS engine pauses naturally.
    // Curly quotes -> ASCII so abbreviation patterns don't need both styles.
    private static func normalizeUnicodeChars(_ text: String) -> String {
        text
            .replacingOccurrences(of: "\u{2019}", with: "'")  // RIGHT SINGLE QUOTATION MARK
            .replacingOccurrences(of: "\u{2018}", with: "'")  // LEFT SINGLE QUOTATION MARK
            .replacingOccurrences(of: "\u{201C}", with: "\"") // LEFT DOUBLE QUOTATION MARK
            .replacingOccurrences(of: "\u{201D}", with: "\"") // RIGHT DOUBLE QUOTATION MARK
            .replacingOccurrences(of: "\u{2014}", with: ", ") // EM DASH -> ", "
            .replacingOccurrences(of: "\u{2013}", with: " to ") // EN DASH -> " to "
            .replacingOccurrences(of: "\u{2026}", with: "...") // HORIZONTAL ELLIPSIS -> three dots
            .replacingOccurrences(of: "\u{00B7}", with: " ")  // MIDDLE DOT (decorative bullet)
    }

    // MARK: - Structural noise removal

    // Decorative scene-break separators: lines whose entire non-whitespace
    // content is symbols, repeated dashes, asterisks, or Unicode ornaments.
    // Examples: "* * *", "-- -- --", diamond, "o o o", "- - -", "***", etc.
    private static func removeDecorativeSeparators(_ text: String) -> String {
        text.replacingOccurrences(
            of: #"(?m)^\s*[-*~=_—–◆◇○●•†‡§¶×✦✧❖✿❧☙♦♠♣♥＊＃\s]{1,40}\s*$"#,
            with: "\n",
            options: .regularExpression
        )
    }

    // Bare integer lines 1-4 digits -> page numbers inserted by EPUB/PDF generators
    private static func removeStandalonePageNumbers(_ text: String) -> String {
        text.replacingOccurrences(
            of: #"(?m)^\s*\d{1,4}\s*$"#,
            with: "",
            options: .regularExpression
        )
    }

    // Standalone roman numerals on their own line (front-matter/back-matter pages).
    // Matches i, ii, iii ... xix, xx, ... up to 20 characters to avoid false positives.
    //
    // Kotlin's version conditionally preserves whitespace-only matches instead of
    // replacing them with "" (see EpubTextPreprocessor.removeRomanNumeralPageNumbers'
    // comment) - a distinction normalizeWhitespace() erases either way, so this port
    // always replaces with "" rather than reproducing that no-op-preserving branch.
    private static func removeRomanNumeralPageNumbers(_ text: String) -> String {
        text.replacingOccurrences(
            of: #"(?mi)^\s*m{0,4}(?:cm|cd|d?c{0,3})(?:xc|xl|l?x{0,3})(?:ix|iv|v?i{0,3})\s*$"#,
            with: "",
            options: .regularExpression
        )
    }

    // "Page 42", "page 42", "p. 42", "pp. 12-14"
    private static func removeLabelledPageNumbers(_ text: String) -> String {
        text
            .replacingOccurrences(of: #"(?i)\bpp?\.?\s*\d+(?:\s*[–\-]\s*\d+)?\b"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"(?i)\bpage\s+\d+\b"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"-\s*\d+\s*-"#, with: "", options: .regularExpression)
    }

    // Inline footnote refs: [1], [23], (1), (23), superscript Unicode digits
    private static func removeFootnoteMarkers(_ text: String) -> String {
        text
            .replacingOccurrences(of: #"\[\d+]"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"\(\d{1,3}\)"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: "[¹²³⁴⁵⁶⁷⁸⁹⁰]+", with: "", options: .regularExpression)
    }

    // Figure/image/table captions that would be read as garbled metadata.
    private static func removeFigureCaptions(_ text: String) -> String {
        text.replacingOccurrences(
            of: #"(?mi)^\s*(?:fig(?:ure)?|illustration|photo(?:graph)?|image|table|plate|chart|graph|map)\s*\.?\s*\d+\s*[.:\-]?[^\n]{0,120}$"#,
            with: "",
            options: .regularExpression
        )
    }

    // Short all-caps lines (<=6 space-separated tokens) are typically running
    // chapter/section headers baked into chapter HTML/PDF layout by
    // poorly-structured source files. Requires at least 2 chars per token to
    // avoid stripping single-letter words.
    private static func removeRunningHeaders(_ text: String) -> String {
        text.replacingOccurrences(
            of: #"(?m)^\s*(?:[A-Z]{2,}(?:\s+[A-Z]{2,}){0,5})\s*$"#,
            with: "",
            options: .regularExpression
        )
    }

    // MARK: - Abbreviation expansion

    // Expand common abbreviations so the TTS engine reads them naturally.
    // Order matters: longer/more-specific patterns first.
    private static func expandAbbreviations(_ text: String) -> String {
        var result = text
        let expansions: [(String, String)] = [
            // Titles
            (#"\bMrs\."#, "Missus"),
            (#"\bMr\."#, "Mister"),
            (#"\bMs\."#, "Miz"),
            (#"\bDr\."#, "Doctor"),
            (#"\bProf\."#, "Professor"),
            (#"\bSt\."#, "Saint"),
            (#"\bRev\."#, "Reverend"),
            (#"\bGen\."#, "General"),
            (#"\bSgt\."#, "Sergeant"),
            (#"\bCpt\."#, "Captain"),
            (#"\bLt\."#, "Lieutenant"),
            (#"\bCol\."#, "Colonel"),
            // Connectives and qualifiers
            (#"\be\.g\."#, "for example"),
            (#"\bi\.e\."#, "that is"),
            (#"\betc\."#, "etcetera"),
            (#"\bvs\."#, "versus"),
            (#"\bviz\."#, "namely"),
            (#"\bcf\."#, "compare"),
            (#"\bapprox\."#, "approximately"),
            (#"\bno\."#, "number"),
            // Bibliographic
            (#"\bed\."#, "edition"),
            (#"\bvol\."#, "volume"),
            (#"\bch\."#, "chapter"),
            (#"\bsec\."#, "section"),
        ]
        for (pattern, replacement) in expansions {
            result = result.replacingOccurrences(of: pattern, with: replacement, options: .regularExpression)
        }
        return result
    }

    // MARK: - Final whitespace normalization

    private static func normalizeWhitespace(_ text: String) -> String {
        text
            .replacingOccurrences(of: #"[ \t]+"#, with: " ", options: .regularExpression)
            .replacingOccurrences(of: #"\n{3,}"#, with: "\n\n", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
