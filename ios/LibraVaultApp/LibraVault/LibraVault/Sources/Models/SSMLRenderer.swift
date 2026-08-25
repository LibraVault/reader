import Foundation

/// Renders `[NarrationSegment]`s into an SSML document for
/// `AVSpeechUtterance(ssmlRepresentation:)`. Part of #499 v2a Phase A — the
/// only consumer of `NarrationSegment` that needs to be XML-aware;
/// everything upstream (`MarkdownDocumentParser`) stays plain Swift data.
///
/// `<emphasis>` tag fidelity on `AVSpeechSynthesizer` is genuinely
/// unverified (Apple's own docs are thin on which SSML tags are actually
/// honored, and support is reported inconsistent across OS versions) —
/// worst case here is a silently-ignored tag (still valid SSML, still
/// parses, just no audible effect), not a crash or parse failure, so it's
/// included rather than withheld pending verification. `<break>` is the one
/// Apple/community consensus treats as reliably supported, and pauses are
/// this design's primary goal regardless of whether emphasis actually comes
/// through audibly on a given OS version — see issue #499's design writeup.
enum SSMLRenderer {
    /// Milliseconds of pause for each `PauseHint` that isn't `.none`. Chosen
    /// to roughly match the pause hierarchy PocketTTS's adapter uses for the
    /// same hints (see issue #638), so both engines feel consistent when a
    /// listener switches between them. `.sentence` still gets a short break
    /// (unlike relying purely on the engine's own end-of-sentence pausing) —
    /// list items don't reliably end in sentence punctuation in the source,
    /// which is exactly why `MarkdownDocumentParser.narrationText` inserts a
    /// literal ". " between them on the flat-text path this mirrors.
    private static let sentencePauseMs = 150
    private static let paragraphPauseMs = 300
    private static let sceneBreakPauseMs = 900

    static func ssml(for segments: [NarrationSegment], languageCode: String?) -> String {
        var body = ""
        for segment in segments {
            if let breakMs = breakMilliseconds(for: segment.pauseBefore) {
                body += "<break time=\"\(breakMs)ms\"/>"
            }
            body += wrapped(escape(segment.text), kind: segment.kind)
        }
        let lang = languageCode.map { " xml:lang=\"\($0)\"" } ?? ""
        return "<speak version=\"1.0\"\(lang)>\(body)</speak>"
    }

    private static func breakMilliseconds(for pause: NarrationSegment.PauseHint) -> Int? {
        switch pause {
        case .none: return nil
        case .sentence: return sentencePauseMs
        case .paragraph: return paragraphPauseMs
        case .sceneBreak: return sceneBreakPauseMs
        }
    }

    private static func wrapped(_ escapedText: String, kind: NarrationSegment.Kind) -> String {
        switch kind {
        case .plain, .heading:
            return escapedText
        case .emphasis, .quote:
            return "<emphasis level=\"moderate\">\(escapedText)</emphasis>"
        }
    }

    /// XML-escapes text for embedding inside an SSML document. Order matters
    /// — `&` must be escaped first, or escaping it after `<`/`>` would
    /// double-escape the `&` those produce (`&lt;` -> `&amp;lt;`).
    private static func escape(_ text: String) -> String {
        text
            .replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "'", with: "&apos;")
    }
}
