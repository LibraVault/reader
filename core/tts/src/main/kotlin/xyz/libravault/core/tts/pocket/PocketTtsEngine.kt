package xyz.libravault.core.tts.pocket

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.libravault.core.tts.TtsEngine
import xyz.libravault.core.tts.TtsState
import xyz.libravault.core.tts.TtsStatus
import xyz.libravault.core.tts.TtsVoiceInfo

private const val TAG = "PocketTtsEngine"
private const val SAMPLE_RATE_HZ = 24000

class PocketTtsEngine : TtsEngine {
    private val _state = MutableStateFlow(TtsState())
    override val state: StateFlow<TtsState> = _state.asStateFlow()

    private val _completionEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val completionEvent: SharedFlow<Unit> = _completionEvent.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.Default)
    private val playback = PocketPlayback()

    // TODO: Will be populated once sherpa-onnx models/voices are available
    private var availableVoices: List<TtsVoiceInfo> = emptyList()
    private var selectedVoiceId: String? = null

    override fun initialize() {
        val status = _state.value.status
        if (status != TtsStatus.UNINITIALIZED && status != TtsStatus.ERROR) return

        _state.value = _state.value.copy(status = TtsStatus.INITIALIZING, error = null)

        // TODO: Initialize sherpa-onnx OfflineTts and load models
        // For now, mark as ready (models will be loaded on first use)
        Log.d(TAG, "PocketTtsEngine initialized")
        _state.value = _state.value.copy(
            status = TtsStatus.IDLE,
            availableVoices = availableVoices,
            selectedVoiceId = selectedVoiceId ?: availableVoices.firstOrNull()?.id,
        )
    }

    override fun speak(text: String) {
        val status = _state.value.status
        if (status == TtsStatus.UNINITIALIZED || status == TtsStatus.INITIALIZING) return

        if (selectedVoiceId == null) {
            _state.value = _state.value.copy(status = TtsStatus.ERROR, error = "No voice selected")
            return
        }

        _state.value = _state.value.copy(status = TtsStatus.PLAYING, error = null)

        // TODO: Call sherpa-onnx tts.generateWithConfigAndCallback(text, config, ::onChunk)
        scope.launch {
            try {
                Log.d(TAG, "Speaking text: ${text.take(50)}...")
                // TODO: Generate audio chunks and feed to playback
                playback.stop()
                _state.value = _state.value.copy(status = TtsStatus.IDLE)
                _completionEvent.tryEmit(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Speak failed: ${e.message}", e)
                _state.value = _state.value.copy(status = TtsStatus.ERROR, error = e.message)
            }
        }
    }

    override fun pause() {
        playback.pause()
        _state.value = _state.value.copy(status = TtsStatus.PAUSED)
    }

    override fun resume() {
        if (_state.value.status != TtsStatus.PAUSED) return
        playback.resume()
        _state.value = _state.value.copy(status = TtsStatus.PLAYING)
    }

    override fun stop() {
        playback.stop()
        _state.value = _state.value.copy(status = TtsStatus.IDLE)
    }

    override fun setVoice(voiceId: String) {
        if (availableVoices.any { it.id == voiceId }) {
            selectedVoiceId = voiceId
            _state.value = _state.value.copy(selectedVoiceId = voiceId)
        }
    }

    override fun setSpeechRate(rate: Float) {
        // TODO: Configure generation speed in sherpa-onnx GenerationConfig
        _state.value = _state.value.copy(speechRate = rate)
    }

    override fun shutdown() {
        playback.stop()
        _state.value = TtsState()
    }
}
