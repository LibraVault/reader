package xyz.libravault.core.tts.pocket

import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import xyz.libravault.core.tts.TtsAudioFocusManager
import xyz.libravault.core.tts.TtsEngine
import xyz.libravault.core.tts.TtsState
import xyz.libravault.core.tts.TtsStatus
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PocketTtsEngine"

@Singleton
class PocketTtsEngine @Inject constructor(
    private val modelManager: PocketModelManager,
    private val scope: CoroutineScope,
    private val audioFocusManager: TtsAudioFocusManager,
) : TtsEngine {
    private val _state = MutableStateFlow(TtsState())
    override val state: StateFlow<TtsState> = _state.asStateFlow()

    private val _completionEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val completionEvent: SharedFlow<Unit> = _completionEvent.asSharedFlow()

    private val playback = PocketPlayback()

    private var tts: OfflineTts? = null
    private var selectedVoiceId: String? = null

    override fun initialize() {
        val status = _state.value.status
        if (status != TtsStatus.UNINITIALIZED && status != TtsStatus.ERROR) return

        _state.value = _state.value.copy(status = TtsStatus.INITIALIZING, error = null)

        scope.launch {
            modelManager.ensureModelAvailable().collect { modelStatus ->
                when (modelStatus) {
                    is ModelStatus.Ready -> {
                        try {
                            tts = loadModel(modelStatus.path)
                            selectedVoiceId = PocketVoiceCatalog.DEFAULT_VOICE_ID
                            _state.value = _state.value.copy(
                                status = TtsStatus.IDLE,
                                availableVoices = listOf(PocketVoiceCatalog.DEFAULT_VOICE),
                                selectedVoiceId = selectedVoiceId,
                            )
                            Log.d(TAG, "PocketTtsEngine initialized")
                        } catch (e: Throwable) {
                            // Throwable, not Exception: loading the model is the
                            // first thing to touch the native layer, and its
                            // failures arrive as UnsatisfiedLinkError - an Error.
                            // Catching only Exception let that escape the
                            // coroutine and take the whole app down instead of
                            // surfacing as a TTS error the UI can show. That is
                            // not hypothetical: it is what a JNI package
                            // mismatch did until it was fixed (see Tts.kt).
                            Log.e(TAG, "Failed to load model: ${e.message}", e)
                            _state.value = _state.value.copy(status = TtsStatus.ERROR, error = e.message)
                        }
                    }
                    is ModelStatus.Failed -> {
                        _state.value = _state.value.copy(status = TtsStatus.ERROR, error = modelStatus.error)
                    }
                    // Idle/Downloading: stay INITIALIZING. Settings UI observes
                    // PocketModelManager.ensureModelAvailable() separately for
                    // download-progress display.
                    else -> Unit
                }
            }
        }
    }

    private fun loadModel(modelPath: String): OfflineTts =
        OfflineTts(config = pocketTtsConfig(modelPath))

    override fun speak(text: String) {
        val status = _state.value.status
        if (status == TtsStatus.UNINITIALIZED || status == TtsStatus.INITIALIZING) return

        val ttsInstance = tts
        if (ttsInstance == null || selectedVoiceId == null) {
            _state.value = _state.value.copy(status = TtsStatus.ERROR, error = "No voice selected")
            return
        }

        audioFocusManager.requestFocus { stop() }
        _state.value = _state.value.copy(status = TtsStatus.PLAYING, error = null)

        scope.launch {
            try {
                Log.d(TAG, "Speaking text: ${text.take(50)}...")
                val config = GenerationConfig(
                    speed = _state.value.speechRate,
                    sid = PocketVoiceCatalog.DEFAULT_SPEAKER_ID,
                )
                // Rate comes from the loaded model, never a constant - see
                // PocketPlayback.play's KDoc for the mismatch this caused.
                playback.play(generateChunks(ttsInstance, text, config), ttsInstance.sampleRate()) {
                    _state.value = _state.value.copy(status = TtsStatus.IDLE)
                    _completionEvent.tryEmit(Unit)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Speak failed: ${e.message}", e)
                _state.value = _state.value.copy(status = TtsStatus.ERROR, error = e.message)
            }
        }
    }

    /**
     * Bridges sherpa-onnx's synchronous, callback-driven generation into a
     * [Flow] that [PocketPlayback.play] can stream from as chunks arrive.
     * `generateWithConfigAndCallback` blocks the calling coroutine (on
     * [scope]'s dispatcher, not the caller of [speak]) until synthesis
     * finishes - unlimited buffering decouples generation speed from
     * playback consumption speed so a burst of chunks never blocks the
     * producer waiting on a slow consumer.
     */
    private fun generateChunks(
        ttsInstance: OfflineTts,
        text: String,
        config: GenerationConfig,
    ): Flow<FloatArray> = callbackFlow {
        launch {
            try {
                // Must be SherpaGenerationCallback, not a lambda - see that
                // class for the JNI method-lookup reason.
                ttsInstance.generateWithConfigAndCallback(
                    text,
                    config,
                    SherpaGenerationCallback { samples -> trySend(samples) },
                )
            } catch (e: Exception) {
                close(e)
                return@launch
            }
            close()
        }
        awaitClose { }
    }.buffer(Int.MAX_VALUE)

    override fun pause() {
        playback.pause()
        audioFocusManager.abandonFocus()
        _state.value = _state.value.copy(status = TtsStatus.PAUSED)
    }

    override fun resume() {
        if (_state.value.status != TtsStatus.PAUSED) return
        audioFocusManager.requestFocus { stop() }
        playback.resume()
        _state.value = _state.value.copy(status = TtsStatus.PLAYING)
    }

    override fun stop() {
        playback.stop()
        audioFocusManager.abandonFocus()
        _state.value = _state.value.copy(status = TtsStatus.IDLE)
    }

    override fun setVoice(voiceId: String) {
        if (_state.value.availableVoices.any { it.id == voiceId }) {
            selectedVoiceId = voiceId
            _state.value = _state.value.copy(selectedVoiceId = voiceId)
        }
    }

    override fun setSpeechRate(rate: Float) {
        // Sherpa-onnx applies speed per-generate-call via GenerationConfig.speed
        // (read from state in speak()), not as persistent engine state - no
        // separate native call needed here.
        _state.value = _state.value.copy(speechRate = rate)
    }

    override fun shutdown() {
        playback.stop()
        audioFocusManager.abandonFocus()
        tts?.release()
        tts = null
        _state.value = TtsState()
    }
}
