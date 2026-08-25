import Foundation

/// One narratable unit of text plus the prosody signal that was thrown away
/// before this existed — issue #499's v2a: deriving pause/emphasis hints from
/// source document structure (bold/italic, block quotes, scene breaks)
/// instead of collapsing everything into one flat, structureless string.
///
/// Deliberately coarse: no nested bold+italic distinction (speech doesn't
/// need it), no per-engine voice-switching (PocketTTS is single-speaker;
/// system engines don't get a lever for it either here) — this restores
/// signal that already existed in the source markup, it isn't a general
/// SSML DSL. See the design writeup on issue #499 for the full survey of
/// what each engine can and can't do with this.
struct NarrationSegment: Equatable {
    /// How this segment's text should be voiced, where the engine has any
    /// lever for it at all (system engines via SSML `<emphasis>`; PocketTTS
    /// has none — see issue #638, `.emphasis`/`.quote` render as plain text
    /// there, a permanent capability gap, not a v1 scoping choice).
    enum Kind: Equatable {
        case plain
        case emphasis
        case quote
        case heading
    }

    /// How much of a pause should precede this segment, relative to what the
    /// engine already does on its own for ordinary sentence punctuation.
    /// Ordinal, not milliseconds — each engine's adapter maps this to
    /// whatever concrete mechanism it actually has (an SSML `<break>` for
    /// system engines, a spliced-in silent audio chunk for PocketTTS).
    enum PauseHint: Equatable {
        case none
        case sentence
        case paragraph
        case sceneBreak
    }

    let text: String
    let kind: Kind
    let pauseBefore: PauseHint

    init(text: String, kind: Kind = .plain, pauseBefore: PauseHint = .none) {
        self.text = text
        self.kind = kind
        self.pauseBefore = pauseBefore
    }

    /// Copy-with helper for the common case of a producer building a
    /// segment's text/kind first, then discovering after the fact that it
    /// should carry a pause (e.g. the segment right after a scene break, or
    /// the first of several segments split from one run list).
    func withPauseBefore(_ pauseBefore: PauseHint) -> NarrationSegment {
        NarrationSegment(text: text, kind: kind, pauseBefore: pauseBefore)
    }

    /// Applies a text transform (e.g. `TtsTextNormalizer.clean`) to just this
    /// segment's `.text`, keeping `kind`/`pauseBefore` untouched. Used by
    /// `AppState.chapterSegments(for:)` to run the same cleanup the flat-text
    /// path uses, per-segment rather than on one joined string.
    func cleaned(via transform: (String) -> String) -> NarrationSegment {
        NarrationSegment(text: transform(text), kind: kind, pauseBefore: pauseBefore)
    }
}

extension Array where Element == NarrationSegment {
    /// Flattens segments back to a plain string — the fallback currency for
    /// engines with no segment-aware rendering at all (the default
    /// `TTSEngineProtocol.speak(segments:rate:)` extension), and for
    /// anything that only ever needed plain text.
    ///
    /// Not a naive space-join: a segment's own `text` already carries
    /// whatever spacing existed between inline runs within the same source
    /// block (e.g. "emph" + " text and " + "bold" concatenate cleanly with
    /// no separator), so only `pauseBefore == .paragraph/.sceneBreak`
    /// (block boundaries) get an explicit `"\n\n"`, and `.sentence` (list
    /// items) gets `". "` — matching `MarkdownDocumentParser.narrationText`'s
    /// own joining behavior for those two cases. Not guaranteed
    /// byte-identical to that flat-string path, just a reasonable
    /// reconstruction for engines that can't consume segments directly.
    var plainText: String {
        var result = ""
        for segment in self {
            switch segment.pauseBefore {
            case .none:
                result += segment.text
            case .sentence:
                result += result.isEmpty ? segment.text : ". " + segment.text
            case .paragraph, .sceneBreak:
                result += result.isEmpty ? segment.text : "\n\n" + segment.text
            }
        }
        return result
    }
}
