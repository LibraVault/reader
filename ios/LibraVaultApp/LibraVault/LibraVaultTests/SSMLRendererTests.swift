import XCTest
@testable import LibraVault

final class SSMLRendererTests: XCTestCase {

    func testEmptySegmentsProduceAWellFormedEmptySpeakElement() {
        let ssml = SSMLRenderer.ssml(for: [], languageCode: nil)
        XCTAssertEqual(ssml, "<speak version=\"1.0\"></speak>")
    }

    func testLanguageCodeIsRenderedAsXmlLangAttribute() {
        let ssml = SSMLRenderer.ssml(for: [NarrationSegment(text: "hi")], languageCode: "en-US")
        XCTAssertTrue(ssml.hasPrefix("<speak version=\"1.0\" xml:lang=\"en-US\">"), ssml)
    }

    func testNilLanguageCodeOmitsTheXmlLangAttributeEntirely() {
        let ssml = SSMLRenderer.ssml(for: [NarrationSegment(text: "hi")], languageCode: nil)
        XCTAssertFalse(ssml.contains("xml:lang"), ssml)
    }

    func testPlainSegmentIsNotWrappedInAnyTag() {
        let ssml = SSMLRenderer.ssml(for: [NarrationSegment(text: "plain text", kind: .plain)], languageCode: nil)
        XCTAssertEqual(ssml, "<speak version=\"1.0\">plain text</speak>")
    }

    func testHeadingSegmentIsNotWrappedEitherOnlyItsOwnPauseApplies() {
        let ssml = SSMLRenderer.ssml(
            for: [NarrationSegment(text: "Chapter One", kind: .heading, pauseBefore: .paragraph)],
            languageCode: nil
        )
        XCTAssertEqual(ssml, "<speak version=\"1.0\"><break time=\"300ms\"/>Chapter One</speak>")
    }

    func testEmphasisSegmentIsWrappedInEmphasisTag() {
        let ssml = SSMLRenderer.ssml(for: [NarrationSegment(text: "loud", kind: .emphasis)], languageCode: nil)
        XCTAssertEqual(ssml, "<speak version=\"1.0\"><emphasis level=\"moderate\">loud</emphasis></speak>")
    }

    func testQuoteSegmentIsAlsoWrappedInEmphasisTag() {
        let ssml = SSMLRenderer.ssml(for: [NarrationSegment(text: "quoted", kind: .quote)], languageCode: nil)
        XCTAssertEqual(ssml, "<speak version=\"1.0\"><emphasis level=\"moderate\">quoted</emphasis></speak>")
    }

    func testNonePauseEmitsNoBreakTag() {
        let ssml = SSMLRenderer.ssml(for: [NarrationSegment(text: "x", pauseBefore: .none)], languageCode: nil)
        XCTAssertFalse(ssml.contains("<break"), ssml)
    }

    func testSentenceParagraphAndSceneBreakPausesEmitDistinctBreakDurations() {
        let sentence = SSMLRenderer.ssml(for: [NarrationSegment(text: "x", pauseBefore: .sentence)], languageCode: nil)
        let paragraph = SSMLRenderer.ssml(for: [NarrationSegment(text: "x", pauseBefore: .paragraph)], languageCode: nil)
        let sceneBreak = SSMLRenderer.ssml(for: [NarrationSegment(text: "x", pauseBefore: .sceneBreak)], languageCode: nil)

        XCTAssertTrue(sentence.contains("<break time=\"150ms\"/>"), sentence)
        XCTAssertTrue(paragraph.contains("<break time=\"300ms\"/>"), paragraph)
        XCTAssertTrue(sceneBreak.contains("<break time=\"900ms\"/>"), sceneBreak)
        // Scene break is strictly the longest pause, paragraph strictly
        // longer than sentence — the actual pause hierarchy matters more
        // than the exact millisecond values, which are tunable by ear.
    }

    func testMultipleSegmentsConcatenateInOrder() {
        let ssml = SSMLRenderer.ssml(
            for: [
                NarrationSegment(text: "First. ", kind: .plain),
                NarrationSegment(text: "Second", kind: .emphasis, pauseBefore: .paragraph),
            ],
            languageCode: nil
        )
        XCTAssertEqual(
            ssml,
            "<speak version=\"1.0\">First. <break time=\"300ms\"/><emphasis level=\"moderate\">Second</emphasis></speak>"
        )
    }

    // MARK: - XML escaping

    func testAmpersandIsEscapedFirstSoItIsNotDoubleEscapedByLtGtEscaping() {
        let ssml = SSMLRenderer.ssml(for: [NarrationSegment(text: "Tom & Jerry")], languageCode: nil)
        XCTAssertTrue(ssml.contains("Tom &amp; Jerry"), ssml)
        XCTAssertFalse(ssml.contains("&amp;amp;"), ssml)
    }

    func testAngleBracketsAreEscaped() {
        let ssml = SSMLRenderer.ssml(for: [NarrationSegment(text: "5 < 10 > 2")], languageCode: nil)
        XCTAssertTrue(ssml.contains("5 &lt; 10 &gt; 2"), ssml)
    }

    func testQuotesAndApostrophesAreEscaped() {
        let ssml = SSMLRenderer.ssml(for: [NarrationSegment(text: #"She said "hi", it's fine"#)], languageCode: nil)
        XCTAssertTrue(ssml.contains("&quot;hi&quot;"), ssml)
        XCTAssertTrue(ssml.contains("it&apos;s"), ssml)
    }

    func testTextThatLooksLikeAnSsmlTagIsNeutralisedNotInjected() {
        // A book could legitimately contain literal "<break time="/>" as
        // prose (a code sample, a quoted markup example) — it must render
        // as inert escaped text, never as an actual SSML control the
        // synthesizer would obey.
        let ssml = SSMLRenderer.ssml(for: [NarrationSegment(text: "<break time=\"5000ms\"/>")], languageCode: nil)
        XCTAssertFalse(ssml.contains("<break time=\"5000ms\"/>"), "malicious/incidental markup must not survive unescaped: \(ssml)")
        XCTAssertTrue(ssml.contains("&lt;break time=&quot;5000ms&quot;/&gt;"), ssml)
    }
}
