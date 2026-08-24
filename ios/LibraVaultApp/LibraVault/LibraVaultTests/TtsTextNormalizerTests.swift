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

    // MARK: - Reliability: recall/precision against injected clutter
    //
    // The tests above each pin one hand-picked example. These check the same
    // patterns against real-feeling prose instead of single sentences, and
    // against every clutter type at once per base passage - closer to what a
    // real chapter looks like. Ported from Android's
    // EpubTextPreprocessorTest.kt, which ran the same regex chain (verified
    // to behave identically - see that file's KDoc) against two real
    // public-domain EPUBs (Pride and Prejudice, On the Origin of Species -
    // ~433K words combined) during development: 0.37%/0.04% of alphabetic
    // words disappeared, and every single one traced to either an intended
    // abbreviation expansion or an intended clutter category (chapter
    // markers, table-of-contents roman numerals, decorative title-page line
    // breaks) - no case of real story prose silently eaten was found. One
    // narrow, low-severity, accepted limitation did surface: the labelled-
    // page-number pattern can't distinguish an e-reader page marker from a
    // bibliographic page citation embedded in a footnote's own body text
    // (e.g. "tom. ii. page 405, 1859)"), and strips just the "page 405"
    // fragment, leaving mildly awkward phrasing rather than a wrong or
    // missing word - see the citation regression test below, which pins
    // that this is a *known* tradeoff, not new breakage.

    private static let basePassages = [
        "It was the best of times, it was the worst of times, it was the age of wisdom, it was the age of foolishness.",
        "Mr. Bennet was among the earliest of those who waited on Mr. Bingley. He had always intended to visit him, though to the last always assuring his wife that he should not go.",
        "Call me Ishmael. Some years ago, never mind how long precisely, having little or no money in my purse, I thought I would sail about a little and see the watery part of the world.",
    ]

    private struct ClutterInjection {
        let label: String
        let inject: (String) -> String
        let marker: String
    }

    private static let clutterInjections: [ClutterInjection] = [
        ClutterInjection(label: "standalone page number", inject: { "\($0)\n47\n" }, marker: "\n47\n"),
        ClutterInjection(label: "labelled page number", inject: { "\($0) It continues on page 12." }, marker: "page 12"),
        ClutterInjection(label: "bracketed footnote marker", inject: { "\($0)[3] More follows." }, marker: "[3]"),
        ClutterInjection(label: "superscript footnote marker", inject: { "\($0)\u{00B2} More follows." }, marker: "\u{00B2}"),
        ClutterInjection(label: "figure caption", inject: { "\($0)\nFigure 4: A curious diagram.\n" }, marker: "Figure 4"),
        ClutterInjection(label: "running header", inject: { "CHAPTER FIVE\n\($0)" }, marker: "CHAPTER FIVE"),
        ClutterInjection(label: "roman numeral chapter marker", inject: { "CHAPTER XI\n\($0)" }, marker: "CHAPTER XI"),
        ClutterInjection(label: "decorative separator", inject: { "\($0)\n* * *\n" }, marker: "* * *"),
        ClutterInjection(label: "roman numeral page", inject: { "\($0)\nxiv\n" }, marker: "\nxiv\n"),
    ]

    /// Alphabetic tokens (3+ chars), lowercased - the same metric used to measure
    /// real-corpus word loss during development (see this test class's comment above).
    private static func significantWords(_ text: String) -> Set<String> {
        guard let regex = try? NSRegularExpression(pattern: "[A-Za-z]{3,}") else { return [] }
        let ns = text as NSString
        return Set(regex.matches(in: text, range: NSRange(location: 0, length: ns.length))
            .map { ns.substring(with: $0.range).lowercased() })
    }

    func testEveryClutterInjectionIsRemovedRecallWhileBaseProseSurvivesPrecisionAcrossAllBasePassages() {
        for base in Self.basePassages {
            let baseWords = Self.significantWords(base)
            for injection in Self.clutterInjections {
                let augmented = injection.inject(base)
                let cleaned = TtsTextNormalizer.clean(augmented)

                XCTAssertFalse(
                    cleaned.contains(injection.marker),
                    "[\(injection.label)] injected clutter marker survived cleaning\n  augmented: \(augmented)\n  cleaned: \(cleaned)"
                )

                let lostWords = baseWords.subtracting(Self.significantWords(cleaned))
                XCTAssertTrue(
                    lostWords.isEmpty,
                    "[\(injection.label)] lost base-passage words \(lostWords)\n  base: \(base)\n  augmented: \(augmented)\n  cleaned: \(cleaned)"
                )
            }
        }
    }

    func testIdempotentAcrossEveryBasePassageAndEveryClutterInjection() {
        for base in Self.basePassages {
            XCTAssertEqual(
                TtsTextNormalizer.clean(base),
                TtsTextNormalizer.clean(TtsTextNormalizer.clean(base)),
                "not idempotent on: \(base)"
            )
            for injection in Self.clutterInjections {
                let once = TtsTextNormalizer.clean(injection.inject(base))
                let twice = TtsTextNormalizer.clean(once)
                XCTAssertEqual(once, twice, "[\(injection.label)] not idempotent on: \(base)")
            }
        }
    }

    // MARK: - Reliability: real-corpus-derived edge cases

    func testDoesNotTreatA4DigitYearInParenthesesAsAFootnoteMarker() {
        // The footnote-marker pattern is capped at 1-3 digits specifically so
        // citation years like "(2020)" survive - confirmed intentional via
        // the {1,3} bound, not a coincidence worth losing to a future edit.
        let result = TtsTextNormalizer.clean("The theory (2020) was well received by critics.")
        XCTAssertTrue(result.contains("(2020)"), "4-digit parenthetical year should survive, got: \(result)")
    }

    func testDoesNotTreatAPunctuatedAllCapsExclamationAsARunningHeader() {
        // Real dialogue ("STOP!" on its own line) must survive - the running-
        // header pattern only matches a line that is ALL-CAPS tokens and
        // nothing else, so trailing punctuation should protect it.
        let result = TtsTextNormalizer.clean("She froze.\nSTOP!\nHe didn't listen.")
        XCTAssertTrue(result.contains("STOP!"), "punctuated all-caps dialogue should survive, got: \(result)")
    }

    func testKnownLimitationPageCitationInsideFootnoteBodyTextGetsPartiallyStripped() {
        // Found via real-corpus testing (On the Origin of Species): a page
        // number inside a bibliographic citation, sitting in a footnote's own
        // body text, is indistinguishable from an e-reader page marker to this
        // regex and gets removed - the footnote body itself is otherwise left
        // alone (footnote body text is explicitly out of scope, see this
        // class's comment above). Result reads slightly awkwardly rather than
        // losing meaning. Pinned here as an accepted, known tradeoff so a
        // future change to this behavior is deliberate, not an accidental
        // side effect of an unrelated regex edit.
        let result = TtsTextNormalizer.clean(
            "I have taken the date from Saint-Hilaire's (\"Hist. Nat. Générale\", tom. ii. page 405, 1859) history."
        )
        XCTAssertFalse(result.contains("page 405"), "documents current (accepted) behavior, got: \(result)")
    }
}
