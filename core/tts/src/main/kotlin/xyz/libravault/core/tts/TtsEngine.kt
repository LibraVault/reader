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
    fun pause()
    fun resume()
    fun stop()
    fun setVoice(voiceId: String)
    fun setSpeechRate(rate: Float)
    fun shutdown()
}
