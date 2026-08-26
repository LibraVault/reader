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

// Silence durations for NarrationSegment.PauseHint (#499 v2a Phase C, #636) — ordinal
// hints translated to concrete gaps via TextToSpeech.playSilentUtterance. Tuned by ear
// against ordinary sentence-final pauses the engine already inserts on its own; not
// derived from any spec.
private const val PAUSE_SENTENCE_MS = 150L
private const val PAUSE_PARAGRAPH_MS = 500L
private const val PAUSE_SCENE_BREAK_MS = 900L

/**
 * One queued unit of Android `TextToSpeech` playback — either speakable text or a
 * deliberate silence. [AndroidTtsEngine.speak] (both the flat-text and the segment-aware
 * overload) reduce down to a `List<PlaybackItem>` so pause/resume/stop/rate-change only
 * need to reason about one queue shape instead of two.
 */
internal sealed interface PlaybackItem {
    data class Speech(val text: String) : PlaybackItem
    data class Silence(val durationMs: Long) : PlaybackItem
}

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

    // The full queued sequence for the current speak() call — speech chunks and, for
    // the segment-aware overload, silences interleaved between them per PauseHint.
    private var items: List<PlaybackItem> = emptyList()
    private var currentItemIndex: Int = 0
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
        speakItems(splitIntoUtterances(text).map { PlaybackItem.Speech(it) })
    }

    override fun speak(segments: List<NarrationSegment>) {
        speakItems(buildPlaybackItems(segments))
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
        enqueueFrom(engine, currentItemIndex)
    }

    override fun stop() {
        utteranceGeneration++
        tts?.stop()
        audioFocusManager.abandonFocus()
        items = emptyList()
        currentItemIndex = 0
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
            enqueueFrom(engine, currentItemIndex)
        }
    }

    override fun shutdown() {
        tts?.shutdown()
        tts = null
        audioFocusManager.abandonFocus()
        _state.value = TtsState()
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun speakItems(newItems: List<PlaybackItem>) {
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
        items = newItems
        currentItemIndex = 0
        _state.value = _state.value.copy(status = TtsStatus.PLAYING, error = null)
        enqueueFrom(engine, 0)
    }

    /**
     * Enqueues every remaining item from [fromIndex] onward via `QUEUE_ADD`, so Android's
     * own utterance queue plays the whole sequence — silences and speech chunks
     * interleaved in queue order — without this class re-triggering the next item from
     * `onDone` (the model the single-utterance-at-a-time [speak] path used before #636;
     * that doesn't generalize to a pre-computed silence/speech sequence, since a silence
     * item has no next item to chain from other than the queue itself).
     *
     * Called fresh (from index 0, or from [currentItemIndex] on resume/rate-change)
     * every time playback needs to (re)start, since [TextToSpeech.stop] — called by
     * [pause], [setSpeechRate], and before a new [speakItems] call — flushes Android's
     * entire queue, not just the item currently playing.
     */
    private fun enqueueFrom(engine: TextToSpeech, fromIndex: Int) {
        if (fromIndex >= items.size) {
            _state.value = _state.value.copy(status = TtsStatus.IDLE)
            _completionEvent.tryEmit(Unit)
            return
        }
        for (index in fromIndex..items.lastIndex) {
            // Encode generation and item index into utteranceId so onDone can reject
            // stale callbacks and track queue progress.
            val id = "${utteranceGeneration}_$index"
            when (val item = items[index]) {
                is PlaybackItem.Speech -> engine.speak(item.text, TextToSpeech.QUEUE_ADD, null, id)
                is PlaybackItem.Silence ->
                    engine.playSilentUtterance(item.durationMs, TextToSpeech.QUEUE_ADD, id)
            }
        }
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
                val index = utteranceId.substringAfter('_').toIntOrNull() ?: return@post
                currentItemIndex = index + 1
                if (currentItemIndex >= items.size) {
                    _state.value = _state.value.copy(status = TtsStatus.IDLE)
                    _completionEvent.tryEmit(Unit)
                }
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

    private fun buildPlaybackItems(segments: List<NarrationSegment>): List<PlaybackItem> =
        Companion.buildPlaybackItems(segments)

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

        private fun pauseDurationMs(hint: NarrationSegment.PauseHint): Long? = when (hint) {
            NarrationSegment.PauseHint.NONE -> null
            NarrationSegment.PauseHint.SENTENCE -> PAUSE_SENTENCE_MS
            NarrationSegment.PauseHint.PARAGRAPH -> PAUSE_PARAGRAPH_MS
            NarrationSegment.PauseHint.SCENE_BREAK -> PAUSE_SCENE_BREAK_MS
        }

        // Visible for testing. A segment's own text can still exceed
        // MAX_UTTERANCE_CHARS (a long unbroken paragraph), so its chunks still go
        // through splitIntoUtterances — the silence for pauseBefore only precedes the
        // first chunk, never gets repeated for a segment's internal chunk splits.
        internal fun buildPlaybackItems(segments: List<NarrationSegment>): List<PlaybackItem> {
            val items = mutableListOf<PlaybackItem>()
            for (segment in segments) {
                pauseDurationMs(segment.pauseBefore)?.let { items += PlaybackItem.Silence(it) }
                items += splitIntoUtterances(segment.text)
                    .filter { it.isNotBlank() }
                    .map { PlaybackItem.Speech(it) }
            }
            return items
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
