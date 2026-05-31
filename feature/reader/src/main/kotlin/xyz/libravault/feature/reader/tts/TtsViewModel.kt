package xyz.libravault.feature.reader.tts

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import xyz.libravault.core.tts.TtsEngine
import xyz.libravault.core.tts.TtsState
import xyz.libravault.core.tts.TtsStatus
import javax.inject.Inject

@HiltViewModel
class TtsViewModel @Inject constructor(
    private val engine: TtsEngine,
) : ViewModel() {

    val state: StateFlow<TtsState> = engine.state
    val completionEvent: SharedFlow<Unit> = engine.completionEvent

    // Text staged for playback — set by the reader when the chapter content is ready.
    private var stagedText: String = ""

    fun initializeIfNeeded() {
        val status = engine.state.value.status
        if (status == TtsStatus.UNINITIALIZED || status == TtsStatus.ERROR) {
            engine.initialize()
        }
    }

    fun setContent(text: String) {
        stagedText = text
    }

    fun play() {
        when (engine.state.value.status) {
            TtsStatus.PAUSED -> engine.resume()
            TtsStatus.PLAYING -> { /* already playing */ }
            else -> {
                if (stagedText.isNotBlank()) {
                    engine.speak(stagedText)
                }
            }
        }
    }

    /** Atomically replaces current content and starts speaking from the beginning.
     *  Use this when the user flips to a new chapter while TTS is already playing. */
    fun restart(text: String) {
        stagedText = text
        engine.speak(text)
    }

    fun pause() = engine.pause()

    fun stop() = engine.stop()

    fun setVoice(voiceId: String) = engine.setVoice(voiceId)

    fun setSpeechRate(rate: Float) = engine.setSpeechRate(rate.coerceIn(0.5f, 3.0f))

    override fun onCleared() {
        engine.stop()
    }
}
