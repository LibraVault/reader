package xyz.libravault.core.cloudtts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import xyz.libravault.core.tts.TtsAudioFocusManager
import xyz.libravault.core.tts.TtsEngine
import xyz.libravault.core.tts.TtsEngineType
import xyz.libravault.core.tts.TtsPreferences
import xyz.libravault.core.tts.TtsState
import xyz.libravault.core.tts.TtsStatus
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

// Chunking mirrors AndroidTtsEngine's own MAX_UTTERANCE_CHARS — PRD §3
// ("no new segmentation logic needed"). Not shared as a public constant
// between the two engines since core:cloudtts intentionally doesn't depend
// on AndroidTtsEngine directly; this is a reasonable, independently
// documented value for the same reason (avoid one HTTP call per whole
// chapter, keep individual vendor requests small).
private const val MAX_CHUNK_CHARS = 3900

/**
 * `TtsEngineType.CLOUD`'s [TtsEngine]. Bound into `core:tts`'s
 * [xyz.libravault.core.tts.TtsEngineFactory] multibinding map from this
 * module's own (unflavored) Hilt module — see [TtsEngineFactory][
 * xyz.libravault.core.tts.TtsEngineFactory]'s class doc for why.
 *
 * [CloudTtsGate.observeCanUseCloudTts] is re-checked before every chunk in
 * [speak], not just once at the top — a subscription lapsing or consent
 * being revoked mid-session stops new cloud calls immediately. On gate
 * failure, missing provider/voice selection, missing credentials, or any
 * HTTP failure, delegates the rest of the utterance to the on-device
 * fallback engine ([TtsEngineType.ANDROID], resolved from the SAME
 * multibinding map this class is itself bound into — Provider-based
 * multibinding maps can reference their own other entries without a cycle,
 * so no separate DI qualifier is needed) rather than retrying cloud or
 * silently stalling (PRD §3).
 */
@Singleton
class CloudTtsEngine @Inject constructor(
    private val gate: CloudTtsGate,
    private val cloudTtsProvider: CloudTtsProvider,
    private val apiKeyStore: CloudApiKeyStore,
    private val preferences: TtsPreferences,
    private val engines: Map<TtsEngineType, @JvmSuppressWildcards Provider<TtsEngine>>,
    private val scope: CoroutineScope,
    private val audioFocusManager: TtsAudioFocusManager,
    private val playback: CloudPlayback,
) : TtsEngine {

    private val _state = MutableStateFlow(TtsState())
    override val state: StateFlow<TtsState> = _state.asStateFlow()

    private val _completionEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val completionEvent: SharedFlow<Unit> = _completionEvent.asSharedFlow()

    private val _stopEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val stopEvent: SharedFlow<Unit> = _stopEvent.asSharedFlow()

    private val fallbackEngine: TtsEngine by lazy {
        engines[TtsEngineType.ANDROID]?.get()
            ?: error("No on-device fallback (TtsEngineType.ANDROID) bound — cannot safely run CloudTtsEngine without one")
    }

    private var isFallenBack = false
    private var fallbackForwardingJob: Job? = null
    private var speakJob: Job? = null

    /** Why the most recent fallback happened, if any — e.g. "cloud voices
     * gate is closed", "no saved credentials for OPENAI". Settings UI
     * (follow-up PR) can surface this; not part of [TtsState] since it's
     * diagnostic, not itself playback status. */
    var lastFallbackReason: String? = null
        private set

    override fun initialize() {
        // No network call here — nothing to fetch a voice catalog from
        // without a selected provider/credentials, and the PRD's five voice
        // presets are fixed per vendor (Settings UI follow-up owns
        // presenting them, not this engine).
        _state.value = _state.value.copy(status = TtsStatus.IDLE, error = null)
    }

    override fun speak(text: String) {
        speakJob?.cancel()
        fallbackForwardingJob?.cancel()
        isFallenBack = false
        audioFocusManager.requestFocus { stop() }
        _state.value = _state.value.copy(status = TtsStatus.PLAYING, error = null)

        speakJob = scope.launch {
            val chunks = splitIntoChunks(text)
            speakChunk(chunks, 0)
        }
    }

    private suspend fun speakChunk(chunks: List<String>, index: Int) {
        if (index >= chunks.size) {
            _state.value = _state.value.copy(status = TtsStatus.IDLE)
            _completionEvent.tryEmit(Unit)
            return
        }

        if (!gate.observeCanUseCloudTts().first()) {
            fallBackTo(chunks, index, "cloud voices gate is closed (subscription lapsed or consent revoked)")
            return
        }
        val providerId = preferences.selectedCloudProviderFlow.first()
            ?.let { runCatching { CloudProviderId.valueOf(it) }.getOrNull() }
        val voiceId = preferences.selectedVoiceFlow.first()
        if (providerId == null || voiceId == null) {
            fallBackTo(chunks, index, "no cloud voice selected")
            return
        }
        val credentials = apiKeyStore.loadCredentials(providerId)
        if (credentials == null) {
            fallBackTo(chunks, index, "no saved credentials for $providerId")
            return
        }

        val result = cloudTtsProvider.synthesize(providerId, chunks[index], voiceId, credentials)
        val audioBytes = result.getOrElse {
            fallBackTo(chunks, index, "cloud synthesis failed: ${it.message}")
            return
        }

        playback.play(
            audioBytes,
            onCompletion = { scope.launch { speakChunk(chunks, index + 1) } },
            onError = { message -> scope.launch { fallBackTo(chunks, index, "playback error: $message") } },
        )
    }

    /** Delegates chunks `[index, chunks.size)` to the on-device engine and
     * forwards its state/events into this engine's own flows for as long as
     * fallback stays active, so a caller observing THIS engine's flows sees
     * accurate status rather than freezing at the moment of fallback.
     *
     * Deliberately no `android.util.Log` call here (unlike `AndroidTtsEngine`/
     * `PocketTtsEngine`): [reason] is instead surfaced via [lastFallbackReason]
     * — real diagnostic value (visible to QA/UI, not just logcat) without
     * `core:cloudtts` needing Robolectric just to unit-test this one path
     * (`android.util.Log` throws "not mocked" in a plain JVM test — same
     * reasoning as [RealCloudApiKeyStore] using `java.util.Base64`, not
     * `android.util.Base64`). */
    private fun fallBackTo(chunks: List<String>, index: Int, reason: String) {
        lastFallbackReason = reason
        isFallenBack = true
        val remainingText = chunks.subList(index, chunks.size).joinToString(" ")

        fallbackForwardingJob?.cancel()
        fallbackForwardingJob = scope.launch {
            launch { fallbackEngine.state.collect { _state.value = it } }
            launch { fallbackEngine.completionEvent.collect { _completionEvent.tryEmit(Unit) } }
            launch { fallbackEngine.stopEvent.collect { _stopEvent.tryEmit(Unit) } }
        }
        if (fallbackEngine.state.value.status == TtsStatus.UNINITIALIZED) {
            fallbackEngine.initialize()
        }
        fallbackEngine.speak(remainingText)
    }

    override fun pause() {
        if (isFallenBack) {
            fallbackEngine.pause()
        } else {
            playback.pause()
            _state.value = _state.value.copy(status = TtsStatus.PAUSED)
        }
    }

    override fun resume() {
        if (isFallenBack) {
            fallbackEngine.resume()
        } else {
            playback.resume()
            _state.value = _state.value.copy(status = TtsStatus.PLAYING)
        }
    }

    override fun stop() {
        speakJob?.cancel()
        fallbackForwardingJob?.cancel()
        if (isFallenBack) {
            fallbackEngine.stop()
            isFallenBack = false
        }
        playback.stop()
        audioFocusManager.abandonFocus()
        _state.value = _state.value.copy(status = TtsStatus.IDLE)
        _stopEvent.tryEmit(Unit)
    }

    override fun setVoice(voiceId: String) {
        _state.value = _state.value.copy(selectedVoiceId = voiceId)
        if (isFallenBack) fallbackEngine.setVoice(voiceId)
    }

    override fun setSpeechRate(rate: Float) {
        _state.value = _state.value.copy(speechRate = rate)
        if (isFallenBack) fallbackEngine.setSpeechRate(rate)
    }

    override fun shutdown() {
        speakJob?.cancel()
        fallbackForwardingJob?.cancel()
        playback.stop()
        audioFocusManager.abandonFocus()
        _state.value = TtsState()
    }

    private fun splitIntoChunks(text: String): List<String> {
        if (text.length <= MAX_CHUNK_CHARS) return listOf(text)

        val chunks = mutableListOf<String>()
        var remaining = text.trim()
        while (remaining.isNotEmpty()) {
            if (remaining.length <= MAX_CHUNK_CHARS) {
                chunks += remaining
                break
            }
            val window = remaining.substring(0, MAX_CHUNK_CHARS)
            val cut = window.indexOfLast { it == '.' || it == '!' || it == '?' }
            val splitAt = if (cut > 0) cut + 1 else MAX_CHUNK_CHARS
            chunks += remaining.substring(0, splitAt).trim()
            remaining = remaining.substring(splitAt).trim()
        }
        return chunks
    }
}
