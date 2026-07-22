package xyz.libravault.core.tts

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface TtsEngine {
    val state: StateFlow<TtsState>

    /** Emits Unit when all queued utterances finish naturally (not on stop/pause). */
    val completionEvent: SharedFlow<Unit>

    fun initialize()
    fun speak(text: String)
    fun pause()
    fun resume()
    fun stop()
    fun setVoice(voiceId: String)
    fun setSpeechRate(rate: Float)
    fun shutdown()
}
