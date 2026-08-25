import XCTest
@testable import LibraVault

final class NarrationSegmentTests: XCTestCase {

    // MARK: - withPauseBefore

    func testWithPauseBeforeReplacesOnlyThePauseKeepingTextAndKind() {
        let original = NarrationSegment(text: "hello", kind: .emphasis, pauseBefore: .none)
        let updated = original.withPauseBefore(.sceneBreak)

        XCTAssertEqual(updated, NarrationSegment(text: "hello", kind: .emphasis, pauseBefore: .sceneBreak))
    }

    // MARK: - cleaned(via:)

    func testCleanedAppliesTheTransformToTextOnlyKeepingKindAndPause() {
        let original = NarrationSegment(text: "  spaced  ", kind: .quote, pauseBefore: .paragraph)
        let cleaned = original.cleaned { $0.trimmingCharacters(in: .whitespaces) }

        XCTAssertEqual(cleaned, NarrationSegment(text: "spaced", kind: .quote, pauseBefore: .paragraph))
    }

    // MARK: - plainText

    func testPlainTextOnEmptyArrayIsEmptyString() {
        XCTAssertEqual(([] as [NarrationSegment]).plainText, "")
    }

    func testPlainTextConcatenatesNonePauseSegmentsWithNoSeparator() {
        // Mirrors within-block run splitting, where each segment's own text
        // already carries whatever spacing existed between runs.
        let segments = [
            NarrationSegment(text: "emph", kind: .emphasis),
            NarrationSegment(text: " text and ", kind: .plain),
            NarrationSegment(text: "bold", kind: .emphasis),
        ]
        XCTAssertEqual(segments.plainText, "emph text and bold")
    }

    func testPlainTextJoinsSentencePauseSegmentsWithPeriodSpace() {
        let segments = [
            NarrationSegment(text: "First", pauseBefore: .none),
            NarrationSegment(text: "Second", pauseBefore: .sentence),
        ]
        XCTAssertEqual(segments.plainText, "First. Second")
    }

    func testPlainTextJoinsParagraphAndSceneBreakPauseSegmentsWithDoubleNewline() {
        let segments = [
            NarrationSegment(text: "Chapter One", kind: .heading, pauseBefore: .paragraph),
            // A real top-level paragraph carries its own .paragraph pause
            // (see MarkdownDocumentParser.chaptersForNarration) — .none is
            // reserved for same-block continuations, not block-to-block
            // transitions like this one.
            NarrationSegment(text: "Before rule.", pauseBefore: .paragraph),
            NarrationSegment(text: "After rule.", pauseBefore: .sceneBreak),
        ]
        XCTAssertEqual(segments.plainText, "Chapter One\n\nBefore rule.\n\nAfter rule.")
    }

    func testPlainTextOnASingleSegmentNeverPrependsASeparator() {
        // The "result.isEmpty" guard — a pause hint on the very first segment
        // shouldn't produce a leading "\n\n" or ". ".
        XCTAssertEqual([NarrationSegment(text: "Only", pauseBefore: .sceneBreak)].plainText, "Only")
    }
}
