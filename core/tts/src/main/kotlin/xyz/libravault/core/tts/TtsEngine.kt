package xyz.libravault.core.tts

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface TtsEngine {
    val state: StateFlow<TtsState>

    /** Emits Unit when all queued utterances finish naturally (not on stop/pause). */
    val completionEvent: SharedFlow<Unit>

    /**
     * Emits Unit whenever [stop] runs - including when it's invoked externally
     * (e.g. [TtsAudioFocusManager]'s onFocusLost callback), not just when a caller
     * holds a reference to this engine and calls it directly. Mutually exclusive
     * with [completionEvent]: natural completion never calls [stop], so callers can
     * use this to detect "playback ended for a reason other than finishing the
     * queued text" without racing [completionEvent] on [state] alone.
     */
    val stopEvent: SharedFlow<Unit>

    fun initialize()
    fun speak(text: String)

    /**
     * Segment-aware overload (#499 v2a, #636) — carries prosody hints
     * ([NarrationSegment.kind]/[NarrationSegment.pauseBefore]) that [speak] alone can't
     * express. Defaults to flattening back to plain text and calling [speak]: an engine
     * that hasn't been taught to render segments (Pocket/Cloud today — see #638 for why
     * Pocket's gap is permanent, not a v1 scoping choice) gets this for free, with no
     * regression and no new capability. [xyz.libravault.core.tts.AndroidTtsEngine] is
     * the one real override.
     */
    fun speak(segments: List<NarrationSegment>) {
        speak(segments.joinToNarrationText())
    }

    fun pause()
    fun resume()
    fun stop()
    fun setVoice(voiceId: String)
    fun setSpeechRate(rate: Float)
    fun shutdown()
}
