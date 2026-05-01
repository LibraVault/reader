package xyz.libravault.feature.reader.tts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import xyz.libravault.core.tts.TtsEngine
import xyz.libravault.core.tts.TtsState
import xyz.libravault.core.tts.TtsStatus
import javax.inject.Inject

@HiltViewModel
class TtsViewModel @Inject constructor(
    private val engine: TtsEngine,
) : ViewModel() {

    val state: StateFlow<TtsState> = engine.state

    // Text staged for playback — set by the reader when the chapter content is ready.
    private var stagedText: String = ""

    fun initializeIfNeeded() {
        if (engine.state.value.status == TtsStatus.UNINITIALIZED) {
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
                if (stagedText.isNotBlank()) engine.speak(stagedText)
            }
        }
    }

    fun pause() = engine.pause()

    fun stop() = engine.stop()

    fun setVoice(voiceId: String) = engine.setVoice(voiceId)

    fun setSpeechRate(rate: Float) = engine.setSpeechRate(rate.coerceIn(0.5f, 3.0f))

    override fun onCleared() {
        // Stop playback but do NOT shut down — the engine is a singleton and may be
        // reused if the user re-enters the reader. Shutdown is handled by the app lifecycle.
        engine.stop()
    }
}
