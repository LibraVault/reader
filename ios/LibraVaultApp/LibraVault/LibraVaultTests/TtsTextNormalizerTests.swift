import XCTest
@testable import LibraVault

/// Unit tests for `TtsTextNormalizer`, the regex chain that cleans chapter
/// text before it's handed to Read Aloud. A faithful Swift port of the
/// pattern set (and this same test set) from Android's
/// `EpubTextPreprocessorTest.kt` - pure string transforms with no
/// AVFoundation/UIKit dependency, so worth pinning precisely, the same way
/// the Kotlin original is: a regex ordering change here is easy to get
/// subtly wrong and would only surface as garbled narration.
final class TtsTextNormalizerTests: XCTestCase {

    // MARK: - Soft hyphens / hyphenated line breaks

    func testRemovesSoftHyphens() {
        let result = TtsTextNormalizer.clean("un\u{00AD}believable")
        XCTAssertEqual(result, "unbelievable")
    }

    func testJoinsHardHyphenatedLineBreaks() {
        let result = TtsTextNormalizer.clean("some-\nwhere over the rainbow")
        XCTAssertTrue(result.contains("somewhere"), "expected joined word, got: \(result)")
    }

    // MARK: - Unicode normalization

    func testNormalizesCurlyQuotesToAscii() {
        let result = TtsTextNormalizer.clean("\u{2018}Hello\u{2019} she said, \u{201C}welcome\u{201D}")
        XCTAssertEqual(result, "'Hello' she said, \"welcome\"")
    }

    func testNormalizesEmDashAndEnDash() {
        let result = TtsTextNormalizer.clean("wait\u{2014}what")
        XCTAssertTrue(result.contains(", "), "em dash should become a comma pause, got: \(result)")

        let enDash = TtsTextNormalizer.clean("pages 10\u{2013}20")
        XCTAssertTrue(
            enDash.contains(" to ") || !enDash.contains("\u{2013}"),
            "en dash should be spelled out, got: \(enDash)"
        )
    }

    func testNormalizesEllipsis() {
        let result = TtsTextNormalizer.clean("wait\u{2026} what")
        XCTAssertTrue(result.contains("..."), "expected three dots, got: \(result)")
    }

    // MARK: - Decorative separators

    func testRemovesDecorativeSceneBreakSeparators() {
        let result = TtsTextNormalizer.clean("End of chapter.\n* * *\nNext chapter begins.")
        XCTAssertFalse(result.contains("*"), "decorative asterisks should be stripped, got: \(result)")
    }

    // MARK: - Page numbers

    func testRemovesStandalonePageNumberLines() {
        let result = TtsTextNormalizer.clean("End of the page.\n42\nStart of next page.")
        XCTAssertFalse(
            result.contains("\n42\n") || result.trimmingCharacters(in: .whitespacesAndNewlines) == "42",
            "bare page number should be removed, got: \(result)"
        )
    }

    func testRemovesLabelledPageNumbers() {
        let result = TtsTextNormalizer.clean("See page 42 for details.")
        XCTAssertFalse(result.contains("page 42"), "labelled page number should be removed, got: \(result)")
    }

    func testPreservesNormalProseThatOnlyCoincidentallyHasDigits() {
        let result = TtsTextNormalizer.clean("She turned twenty-one years old that summer.")
        XCTAssertEqual(result, "She turned twenty-one years old that summer.")
    }

    // MARK: - Footnote markers

    func testRemovesBracketedFootnoteMarkers() {
        let result = TtsTextNormalizer.clean("This is a claim[1] worth citing.")
        XCTAssertFalse(result.contains("[1]"), "footnote marker should be removed, got: \(result)")
    }

    func testRemovesSuperscriptFootnoteDigits() {
        let result = TtsTextNormalizer.clean("This is a claim\u{00B9}\u{00B2} worth citing.")
        XCTAssertFalse(
            result.contains("\u{00B9}") || result.contains("\u{00B2}") || result.contains("\u{00B3}"),
            "superscript digits should be removed, got: \(result)"
        )
    }

    // MARK: - Figure captions

    func testRemovesFigureCaptionsOnTheirOwnLine() {
        let result = TtsTextNormalizer.clean("Some prose.\nFigure 1: A diagram of the system.\nMore prose.")
        XCTAssertFalse(result.contains("Figure 1"), "figure caption should be removed, got: \(result)")
    }

    // MARK: - Running headers

    func testRemovesShortAllCapsRunningHeaders() {
        let result = TtsTextNormalizer.clean("CHAPTER ONE\nIt was a dark and stormy night.")
        XCTAssertFalse(result.contains("CHAPTER ONE"), "running header should be removed, got: \(result)")
        XCTAssertTrue(result.contains("It was a dark and stormy night."))
    }

    func testPreservesNormalSentencesThatAreNotAllCaps() {
        let result = TtsTextNormalizer.clean("It was a dark and stormy night.")
        XCTAssertEqual(result, "It was a dark and stormy night.")
    }

    // MARK: - Abbreviation expansion

    func testExpandsCommonTitles() {
        let result = TtsTextNormalizer.clean("Dr. Smith met Mrs. Jones and Mr. Lee.")
        XCTAssertEqual(result, "Doctor Smith met Missus Jones and Mister Lee.")
    }

    func testExpandsEgIeAndEtc() {
        let result = TtsTextNormalizer.clean("Bring supplies, e.g. water, i.e. the essentials, etc.")
        XCTAssertTrue(result.contains("for example"), "e.g. should expand, got: \(result)")
        XCTAssertTrue(result.contains("that is"), "i.e. should expand, got: \(result)")
        XCTAssertTrue(result.contains("etcetera"), "etc. should expand, got: \(result)")
    }

    // MARK: - Whitespace normalization

    func testCollapsesRunsOfSpacesAndTabs() {
        let result = TtsTextNormalizer.clean("too    many   spaces")
        XCTAssertEqual(result, "too many spaces")
    }

    func testCollapsesThreeOrMoreBlankLinesToASingleBlankLine() {
        let result = TtsTextNormalizer.clean("First.\n\n\n\n\nSecond.")
        XCTAssertEqual(result, "First.\n\nSecond.")
    }

    func testTrimsLeadingAndTrailingWhitespace() {
        let result = TtsTextNormalizer.clean("   \n  padded text  \n   ")
        XCTAssertEqual(result, "padded text")
    }

    // MARK: - Idempotence / no-op on already-clean text

    func testDoesNotMangleAlreadyCleanProse() {
        let text = "It was the best of times, it was the worst of times."
        XCTAssertEqual(TtsTextNormalizer.clean(text), text)
    }
}
