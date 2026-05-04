package xyz.libravault.core.tts

import android.content.Context
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
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AndroidTtsEngine"

// Android TTS has a hard limit of 4000 chars per utterance.
private const val MAX_UTTERANCE_CHARS = 3900

@Singleton
class AndroidTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : TtsEngine {

    private var tts: TextToSpeech? = null

    private val _state = MutableStateFlow(TtsState())
    override val state: StateFlow<TtsState> = _state.asStateFlow()

    private val _completionEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val completionEvent: SharedFlow<Unit> = _completionEvent.asSharedFlow()

    // Full text split into speakable chunks.
    private var utterances: List<String> = emptyList()
    private var currentUtteranceIndex: Int = 0

    override fun initialize() {
        if (_state.value.status != TtsStatus.UNINITIALIZED) return
        _state.value = _state.value.copy(status = TtsStatus.INITIALIZING)

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
        if (_state.value.status == TtsStatus.ERROR) return

        engine.stop()
        utterances = splitIntoUtterances(text)
        currentUtteranceIndex = 0
        _state.value = _state.value.copy(status = TtsStatus.PLAYING, error = null)
        speakNext(engine)
    }

    override fun pause() {
        tts?.stop()
        _state.value = _state.value.copy(status = TtsStatus.PAUSED)
    }

    override fun resume() {
        val engine = tts ?: return
        if (_state.value.status != TtsStatus.PAUSED) return
        _state.value = _state.value.copy(status = TtsStatus.PLAYING)
        speakNext(engine)
    }

    override fun stop() {
        tts?.stop()
        utterances = emptyList()
        currentUtteranceIndex = 0
        _state.value = _state.value.copy(status = TtsStatus.IDLE)
    }

    override fun setVoice(voiceId: String) {
        val engine = tts ?: return
        val voice = engine.voices?.find { it.name == voiceId } ?: return
        engine.voice = voice
        _state.value = _state.value.copy(selectedVoiceId = voiceId)
    }

    override fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate)
        _state.value = _state.value.copy(speechRate = rate)
    }

    override fun shutdown() {
        tts?.shutdown()
        tts = null
        _state.value = TtsState()
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun speakNext(engine: TextToSpeech) {
        val chunk = utterances.getOrNull(currentUtteranceIndex) ?: run {
            _state.value = _state.value.copy(status = TtsStatus.IDLE)
            _completionEvent.tryEmit(Unit)
            return
        }
        engine.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, currentUtteranceIndex.toString())
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) {
            if (_state.value.status != TtsStatus.PLAYING) return
            currentUtteranceIndex++
            val engine = tts ?: return
            speakNext(engine)
        }

        @Deprecated("Deprecated in API 21", ReplaceWith("onError(utteranceId, errorCode)"))
        override fun onError(utteranceId: String?) {
            Log.e(TAG, "TTS error on utterance $utteranceId")
            _state.value = _state.value.copy(status = TtsStatus.ERROR, error = "Playback error")
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

    private fun buildVoiceList(engine: TextToSpeech): List<TtsVoiceInfo> {
        return engine.voices
            ?.filter { !it.isNetworkConnectionRequired }
            ?.sortedWith(compareBy({ it.locale.displayName }, { it.name }))
            ?.map { voice ->
                TtsVoiceInfo(
                    id = voice.name,
                    displayName = "${voice.locale.displayName} — ${voice.name}",
                    locale = voice.locale.toLanguageTag(),
                )
            }
            ?: emptyList()
    }
}
