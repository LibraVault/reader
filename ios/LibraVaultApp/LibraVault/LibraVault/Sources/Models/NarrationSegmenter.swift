import Foundation

/// Shared `[MarkdownBlock]` → `[NarrationSegment]` conversion (#499 v2a). Both
/// `MarkdownDocumentParser` (Phase A, #634) and `EPUBParser`/`VaultEPUBParser`
/// (Phase B, #635) walk the same `MarkdownBlock` tree — EPUB's `parseBlocks`
/// deliberately reuses Markdown's block model (see `EPUBParser.parseBlocks`'s
/// doc comment) — so the prosody-preserving conversion itself has no
/// format-specific logic and belongs in one place rather than as two
/// hand-maintained copies that can drift apart.
enum NarrationSegmenter {
    /// Segments for a flat sequence of blocks (an EPUB chapter's full
    /// `blocks`, or any block list with no further chapter-splitting of its
    /// own), with paragraph-to-paragraph and scene-break pauses inserted
    /// between blocks the same way `MarkdownDocumentParser.chaptersForNarration`
    /// does at the chapter level. Unlike that function, this never splits the
    /// input into multiple chapters — callers that need heading-based
    /// chapter splitting (only Markdown does) keep doing that themselves,
    /// calling `segments(for:baseKind:)` per block as they already did.
    static func segments(forBlocks blocks: [MarkdownBlock]) -> [NarrationSegment] {
        var result: [NarrationSegment] = []
        // A `.thematicBreak` has no text of its own, so its scene-break signal
        // can't attach to a segment in the same iteration — held here and
        // applied to the next block that actually produces one, same as
        // chaptersForNarration's pendingPause.
        var pendingPause: NarrationSegment.PauseHint = .none

        for block in blocks {
            if case .thematicBreak = block {
                pendingPause = .sceneBreak
                continue
            }
            var blockSegments = segments(for: block)
            guard !blockSegments.isEmpty else { continue }
            if pendingPause != .none {
                blockSegments[0] = blockSegments[0].withPauseBefore(pendingPause)
                pendingPause = .none
            } else if !result.isEmpty, blockSegments[0].pauseBefore == .none {
                // Every block here is separated by "\n\n" once flattened via
                // plainText/SSML — a block with no pause hint of its own
                // (headings/blockQuote already set .paragraph; list items 2+
                // already set .sentence) would otherwise run straight into
                // whatever came before it. Only upgrades an unset .none, and
                // only when this isn't the very first segment overall.
                blockSegments[0] = blockSegments[0].withPauseBefore(.paragraph)
            }
            result.append(contentsOf: blockSegments)
        }
        return result
    }

    /// Segment-preserving counterpart to a flat block→text walk — same
    /// content rules as `MarkdownDocumentParser.narrationText` (code/table/
    /// thematic-break/mermaid stay silent, image alt text is spoken), but
    /// keeps emphasis/quote/heading signal instead of flattening everything
    /// to one plain string.
    ///
    /// `baseKind` is the "outer" kind applied to text with no run-level
    /// emphasis of its own — `.plain` normally, `.quote` when recursing into
    /// a `.blockQuote`'s nested blocks. A bold/italic run always renders
    /// `.emphasis` regardless of `baseKind` — emphasis is the more specific
    /// signal and wins over "this happens to be inside a quote".
    static func segments(
        for block: MarkdownBlock,
        baseKind: NarrationSegment.Kind = .plain
    ) -> [NarrationSegment] {
        switch block {
        case let .heading(_, runs):
            return runSegments(runs, baseKind: .heading, pauseBefore: .paragraph)
        case let .paragraph(runs):
            return runSegments(runs, baseKind: baseKind, pauseBefore: .none)
        case let .blockQuote(nested):
            var result = joinedSegments(nested.map { segments(for: $0, baseKind: .quote) })
            if !result.isEmpty {
                result[0] = result[0].withPauseBefore(.paragraph)
            }
            return result
        case let .unorderedList(items), let .orderedList(items, _):
            var result: [NarrationSegment] = []
            for item in items {
                var itemSegments = item.flatMap { segments(for: $0, baseKind: baseKind) }
                guard !itemSegments.isEmpty else { continue }
                // Mirrors narrationText's ". "-joined list items — each item
                // after the first gets a sentence-level pause; the very
                // first item's pause (if any) is whatever the caller
                // already set.
                if !result.isEmpty {
                    itemSegments[0] = itemSegments[0].withPauseBefore(.sentence)
                }
                result.append(contentsOf: itemSegments)
            }
            return result
        case let .image(_, altText):
            return altText.isEmpty ? [] : [NarrationSegment(text: altText, kind: baseKind)]
        case .codeBlock, .thematicBreak, .table, .mermaidDiagram:
            return []
        }
    }

    /// Concatenates several blocks' own segment lists into one, inserting a
    /// `.paragraph` pause before a block's first segment whenever that block
    /// didn't already give it a stronger pause of its own (a nested heading
    /// or blockQuote's own entry pause) — reused so e.g. a multi-paragraph
    /// block quote's paragraphs don't run into each other. Never touches the
    /// very first block's own pause — a leading pause on the whole group, if
    /// any, is the caller's decision, not this function's.
    private static func joinedSegments(_ blockSegmentLists: [[NarrationSegment]]) -> [NarrationSegment] {
        var result: [NarrationSegment] = []
        for blockSegments in blockSegmentLists {
            guard !blockSegments.isEmpty else { continue }
            var segments = blockSegments
            if !result.isEmpty, segments[0].pauseBefore == .none {
                segments[0] = segments[0].withPauseBefore(.paragraph)
            }
            result.append(contentsOf: segments)
        }
        return result
    }

    /// Splits a run list into segments at emphasis-state boundaries — a
    /// bold/italic run (or contiguous run of them) merges into one
    /// `.emphasis` segment rather than one segment per run, since
    /// `NarrationSegment.Kind` deliberately doesn't distinguish bold from
    /// italic. Only the first resulting segment receives `pauseBefore` — an
    /// emphasis shift mid-paragraph isn't a paragraph-level pause the way
    /// the paragraph's own start is.
    private static func runSegments(
        _ runs: [MarkdownInlineRun],
        baseKind: NarrationSegment.Kind,
        pauseBefore: NarrationSegment.PauseHint
    ) -> [NarrationSegment] {
        var result: [NarrationSegment] = []
        var currentText = ""
        var currentEmphasis = false
        for run in runs {
            let emphasized = run.bold || run.italic
            if emphasized != currentEmphasis, !currentText.isEmpty {
                result.append(NarrationSegment(text: currentText, kind: currentEmphasis ? .emphasis : baseKind))
                currentText = ""
            }
            currentEmphasis = emphasized
            currentText += run.text
        }
        if !currentText.isEmpty {
            result.append(NarrationSegment(text: currentText, kind: currentEmphasis ? .emphasis : baseKind))
        }
        if !result.isEmpty {
            result[0] = result[0].withPauseBefore(pauseBefore)
        }
        return result
    }
}
