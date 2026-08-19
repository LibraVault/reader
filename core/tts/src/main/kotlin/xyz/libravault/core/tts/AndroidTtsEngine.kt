package xyz.libravault.core.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AndroidTtsEngine"

// Android TTS has a hard limit of 4000 chars per utterance.
private const val MAX_UTTERANCE_CHARS = 3900

@Singleton
class AndroidTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioFocusManager: TtsAudioFocusManager,
) : TtsEngine {

    private var tts: TextToSpeech? = null

    private val _state = MutableStateFlow(TtsState())
    override val state: StateFlow<TtsState> = _state.asStateFlow()

    private val _completionEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val completionEvent: SharedFlow<Unit> = _completionEvent.asSharedFlow()

    private val _stopEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val stopEvent: SharedFlow<Unit> = _stopEvent.asSharedFlow()

    // All state mutations run on the main thread (via mainHandler or direct call from UI).
    // TTS progress callbacks post to mainHandler so reads and writes are never concurrent.
    // This eliminates the race where onDone fires mid-speak() and queues a chunk from the
    // OLD utterances list with the NEW generation, which Samsung fires onError for.
    private val mainHandler = Handler(Looper.getMainLooper())

    // Full text split into speakable chunks.
    private var utterances: List<String> = emptyList()
    private var currentUtteranceIndex: Int = 0
    // Incremented on every speak() / stop() / pause() call so that onDone
    // callbacks from a previous utterance run are silently ignored.
    private var utteranceGeneration: Int = 0

    override fun initialize() {
        val status = _state.value.status
        if (status != TtsStatus.UNINITIALIZED && status != TtsStatus.ERROR) return
        if (status == TtsStatus.ERROR) {
            tts?.shutdown()
            tts = null
        }
        _state.value = _state.value.copy(status = TtsStatus.INITIALIZING, error = null)

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                engine.language = Locale.getDefault()
                engine.setOnUtteranceProgressListener(progressListener)
                val voices = buildVoiceList(engine)
                _state.value = _state.value.copy(
                    status = TtsStatus.IDLE,
                    availableVoices = voices,
                    selectedVoiceId = engine.defaultVoice?.name,
                )
            } else {
                Log.e(TAG, "TTS init failed with status $status")
                _state.value = _state.value.copy(
                    status = TtsStatus.ERROR,
                    error = "TTS engine failed to initialize (status $status)",
                )
            }
        }
    }

    override fun speak(text: String) {
        val engine = tts ?: return
        val status = _state.value.status
        if (status == TtsStatus.UNINITIALIZED || status == TtsStatus.INITIALIZING) return

        val validationError = validateSelectedVoiceInternal(engine)
        if (validationError != null) {
            _state.value = _state.value.copy(status = TtsStatus.ERROR, error = validationError)
            return
        }

        audioFocusManager.requestFocus { stop() }
        utteranceGeneration++
        engine.stop()
        utterances = splitIntoUtterances(text)
        currentUtteranceIndex = 0
        _state.value = _state.value.copy(status = TtsStatus.PLAYING, error = null)
        speakNext(engine)
    }

    override fun pause() {
        utteranceGeneration++
        tts?.stop()
        audioFocusManager.abandonFocus()
        _state.value = _state.value.copy(status = TtsStatus.PAUSED)
    }

    override fun resume() {
        val engine = tts ?: return
        if (_state.value.status != TtsStatus.PAUSED) return
        audioFocusManager.requestFocus { stop() }
        _state.value = _state.value.copy(status = TtsStatus.PLAYING)
        speakNext(engine)
    }

    override fun stop() {
        utteranceGeneration++
        tts?.stop()
        audioFocusManager.abandonFocus()
        utterances = emptyList()
        currentUtteranceIndex = 0
        _state.value = _state.value.copy(status = TtsStatus.IDLE)
        _stopEvent.tryEmit(Unit)
    }

    override fun setVoice(voiceId: String) {
        val engine = tts ?: return
        val voice = engine.voices?.find { it.name == voiceId } ?: return
        engine.voice = voice
        _state.value = _state.value.copy(selectedVoiceId = voiceId)
    }

    override fun setSpeechRate(rate: Float) {
        val engine = tts ?: return
        engine.setSpeechRate(rate)
        _state.value = _state.value.copy(speechRate = rate)
        // TextToSpeech.setSpeechRate only affects future utterances, not the one currently
        // playing. Bump the generation, stop, and re-speak the current chunk immediately.
        if (_state.value.status == TtsStatus.PLAYING) {
            utteranceGeneration++
            engine.stop()
            speakNext(engine)
        }
    }

    override fun shutdown() {
        tts?.shutdown()
        tts = null
        audioFocusManager.abandonFocus()
        _state.value = TtsState()
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun speakNext(engine: TextToSpeech) {
        val chunk = utterances.getOrNull(currentUtteranceIndex) ?: run {
            _state.value = _state.value.copy(status = TtsStatus.IDLE)
            _completionEvent.tryEmit(Unit)
            return
        }
        // Encode generation into utteranceId so onDone can reject stale callbacks.
        val id = "${utteranceGeneration}_${currentUtteranceIndex}"
        engine.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    // Callbacks arrive on the TTS engine's internal thread. We post every
    // state mutation back to the main thread so it serialises with speak(),
    // stop(), and pause() calls — eliminating all race conditions on shared state.
    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) {
            mainHandler.post {
                val gen = utteranceId?.substringBefore('_')?.toIntOrNull() ?: return@post
                if (gen != utteranceGeneration) return@post
                if (_state.value.status != TtsStatus.PLAYING) return@post
                currentUtteranceIndex++
                val engine = tts ?: return@post
                speakNext(engine)
            }
        }

        @Deprecated("Deprecated in API 21", ReplaceWith("onError(utteranceId, errorCode)"))
        override fun onError(utteranceId: String?) {
            mainHandler.post {
                val gen = utteranceId?.substringBefore('_')?.toIntOrNull() ?: return@post
                if (gen != utteranceGeneration) return@post
                Log.e(TAG, "TTS error on utterance $utteranceId")
                _state.value = _state.value.copy(status = TtsStatus.ERROR, error = "Playback error")
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            mainHandler.post {
                val gen = utteranceId?.substringBefore('_')?.toIntOrNull() ?: return@post
                if (gen != utteranceGeneration) return@post
                Log.e(TAG, "TTS error on utterance $utteranceId (code $errorCode)")
                _state.value = _state.value.copy(status = TtsStatus.ERROR, error = "Playback error ($errorCode)")
            }
        }
    }

    private fun splitIntoUtterances(text: String): List<String> =
        Companion.splitIntoUtterances(text)

    companion object {
        // Visible for testing.
        internal fun splitIntoUtterances(text: String): List<String> {
            if (text.length <= MAX_UTTERANCE_CHARS) return listOf(text)

            val chunks = mutableListOf<String>()
            var remaining = text.trim()

            while (remaining.isNotEmpty()) {
                if (remaining.length <= MAX_UTTERANCE_CHARS) {
                    chunks += remaining
                    break
                }
                // Find last sentence boundary within the limit
                val window = remaining.substring(0, MAX_UTTERANCE_CHARS)
                val cut = window.indexOfLast { it == '.' || it == '!' || it == '?' }
                val splitAt = if (cut > 0) cut + 1 else MAX_UTTERANCE_CHARS
                chunks += remaining.substring(0, splitAt).trim()
                remaining = remaining.substring(splitAt).trim()
            }

            return chunks
        }
    }

    fun validateSelectedVoice(): Result<Unit> {
        val engine = tts ?: return Result.failure(Exception("TTS engine not initialized"))
        val error = validateSelectedVoiceInternal(engine)
        return if (error != null) {
            Result.failure(Exception(error))
        } else {
            Result.success(Unit)
        }
    }

    private fun validateSelectedVoiceInternal(engine: TextToSpeech): String? {
        val voiceId = _state.value.selectedVoiceId ?: return "No voice selected"
        val voice = engine.voices?.find { it.name == voiceId }
            ?: return "Voice '$voiceId' is no longer available"
        if (voice.isNetworkConnectionRequired) {
            return "Voice '$voiceId' requires network connection"
        }
        return null
    }

    private fun buildVoiceList(engine: TextToSpeech): List<TtsVoiceInfo> {
        return engine.voices
            ?.sortedWith(compareBy({ it.locale.displayName }, { it.name }))
            ?.map { voice ->
                TtsVoiceInfo(
                    id = voice.name,
                    displayName = "${voice.locale.displayName} — ${voice.name}",
                    locale = voice.locale.toLanguageTag(),
                    requiresNetwork = voice.isNetworkConnectionRequired,
                )
            }
            ?: emptyList()
    }
}
