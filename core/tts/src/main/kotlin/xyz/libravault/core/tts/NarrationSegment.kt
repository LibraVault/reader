package xyz.libravault.core.tts

/**
 * One narratable unit of text plus the prosody signal that was thrown away before this
 * existed — issue #499's v2a: deriving pause/emphasis hints from source document
 * structure (bold/italic, block quotes, scene breaks) instead of collapsing everything
 * into one flat, structureless string.
 *
 * Kotlin counterpart of iOS's `Sources/Models/NarrationSegment.swift` (#634/#640) — same
 * shape, deliberately kept in sync so both platforms' engines reason about prosody the
 * same way, even though *how* each side derives segments differs (iOS walks a real
 * Markdown AST; [xyz.libravault.feature.reader.markdown.MarkdownTtsTextExtractor] is
 * line-oriented regex, matching this codebase's existing Markdown-handling style).
 *
 * Deliberately coarse: no nested bold+italic distinction (speech doesn't need it), no
 * per-engine voice-switching — this restores signal that already existed in the source
 * markup, it isn't a general SSML DSL. See the design writeup on issue #499.
 */
data class NarrationSegment(
    val text: String,
    val kind: Kind = Kind.PLAIN,
    val pauseBefore: PauseHint = PauseHint.NONE,
) {
    /**
     * How this segment's text should be voiced, where the engine has any lever for it
     * at all. [xyz.libravault.core.tts.AndroidTtsEngine] has no per-segment voicing
     * lever today (system `TextToSpeech` has no documented, reliable way to alter tone
     * for a sub-utterance short of SSML text interpretation, which is unverifiable
     * across OEM engines — see #636) — [kind] is carried through regardless so a future
     * engine (or a smarter Android renderer) has the signal available without another
     * extraction pass.
     */
    enum class Kind {
        PLAIN,
        EMPHASIS,
        QUOTE,
        HEADING,
    }

    /**
     * How much of a pause should precede this segment, relative to what the engine
     * already does on its own for ordinary sentence punctuation. Ordinal, not
     * milliseconds — each engine adapts this to whatever concrete mechanism it actually
     * has (e.g. [xyz.libravault.core.tts.AndroidTtsEngine] splices in a
     * `TextToSpeech.playSilentUtterance` chunk).
     */
    enum class PauseHint {
        NONE,
        SENTENCE,
        PARAGRAPH,
        SCENE_BREAK,
    }
}

/**
 * Flattens segments back to a plain string — the fallback currency for [TtsEngine]
 * implementations with no segment-aware rendering ([TtsEngine.speak]'s default
 * `segments` overload falls back to this), mirroring iOS's `[NarrationSegment].plainText`.
 *
 * Not a naive space-join: a segment's own [NarrationSegment.text] already carries
 * whatever spacing existed between runs within the same source block, so only
 * [NarrationSegment.PauseHint.PARAGRAPH]/[NarrationSegment.PauseHint.SCENE_BREAK] (block
 * boundaries) get an explicit blank line, and [NarrationSegment.PauseHint.SENTENCE] (list
 * items) gets `". "` — matching [xyz.libravault.core.tts]'s iOS counterpart's joining
 * behavior for those two cases. Not guaranteed identical to any single-string extraction
 * path, just a reasonable reconstruction for engines that can't consume segments
 * directly.
 */
fun List<NarrationSegment>.joinToNarrationText(): String {
    val builder = StringBuilder()
    for (segment in this) {
        when (segment.pauseBefore) {
            NarrationSegment.PauseHint.NONE -> builder.append(segment.text)
            NarrationSegment.PauseHint.SENTENCE ->
                if (builder.isEmpty()) builder.append(segment.text) else builder.append(". ").append(segment.text)
            NarrationSegment.PauseHint.PARAGRAPH, NarrationSegment.PauseHint.SCENE_BREAK ->
                if (builder.isEmpty()) builder.append(segment.text) else builder.append("\n\n").append(segment.text)
        }
    }
    return builder.toString()
}
