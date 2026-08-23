package xyz.libravault.core.cloudtts

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.libravault.core.tts.TtsAudioFocusManager
import xyz.libravault.core.tts.TtsEngine
import xyz.libravault.core.tts.TtsEngineType
import xyz.libravault.core.tts.TtsPreferences
import xyz.libravault.core.tts.TtsState
import xyz.libravault.core.tts.TtsStatus
import java.io.File
import javax.inject.Provider

class CloudTtsEngineTest {

    private class FakeTtsEngine : TtsEngine {
        var lastSpokenText: String? = null
        override val state: StateFlow<TtsState> = MutableStateFlow(TtsState(status = TtsStatus.IDLE))
        override val completionEvent: SharedFlow<Unit> = MutableSharedFlow()
        override val stopEvent: SharedFlow<Unit> = MutableSharedFlow()
        override fun initialize() {}
        override fun speak(text: String) {
            lastSpokenText = text
        }
        override fun pause() {}
        override fun resume() {}
        override fun stop() {}
        override fun setVoice(voiceId: String) {}
        override fun setSpeechRate(rate: Float) {}
        override fun shutdown() {}
    }

    private class FakeCloudTtsProvider(
        private val result: Result<ByteArray> = Result.success(byteArrayOf(1, 2, 3)),
    ) : CloudTtsProvider {
        var synthesizeCallCount = 0
        override suspend fun synthesize(
            provider: CloudProviderId,
            text: String,
            voiceId: String,
            credentials: Map<String, String>,
        ): Result<ByteArray> {
            synthesizeCallCount++
            return result
        }
        override suspend fun validateKey(provider: CloudProviderId, credentials: Map<String, String>): Result<Unit> =
            Result.success(Unit)
    }

    /** Never returns — suspends forever until cancelled, simulating a
     * genuinely in-flight network call for cancellation testing. */
    private class SuspendingCloudTtsProvider : CloudTtsProvider {
        override suspend fun synthesize(
            provider: CloudProviderId,
            text: String,
            voiceId: String,
            credentials: Map<String, String>,
        ): Result<ByteArray> = awaitCancellation()
        override suspend fun validateKey(provider: CloudProviderId, credentials: Map<String, String>): Result<Unit> =
            Result.success(Unit)
    }

    private class FakeCloudApiKeyStore(private val stored: Map<String, String>? = null) : CloudApiKeyStore {
        override suspend fun saveCredentials(provider: CloudProviderId, credentials: Map<String, String>) = Unit
        override suspend fun loadCredentials(provider: CloudProviderId): Map<String, String>? = stored
        override suspend fun clearCredentials(provider: CloudProviderId) = Unit
    }

    private class FakeCloudPlayback : CloudPlayback {
        var playedBytes: ByteArray? = null
        var onCompletion: (() -> Unit)? = null
        override fun play(audioBytes: ByteArray, onCompletion: () -> Unit, onError: (String) -> Unit) {
            playedBytes = audioBytes
            this.onCompletion = onCompletion
        }
        override fun pause() {}
        override fun resume() {}
        override fun stop() {}
    }

    // DataStore normally does its file I/O on a real Dispatchers.IO-backed
    // scope — invisible to advanceUntilIdle()'s virtual clock. Since
    // CloudTtsEngine reads preferences from inside a coroutine launched on
    // the test's (virtual-time) scope, DataStore's own internal work must
    // run on that SAME scope too, or a read can still be in flight on a real
    // thread the instant advanceUntilIdle() returns and the test asserts.
    private fun preferences(tempDir: File, scope: CoroutineScope): TtsPreferences =
        TtsPreferences(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(tempDir, "test_tts_preferences.preferences_pb") },
            ),
        )

    private fun fakeAudioFocusManager(): TtsAudioFocusManager {
        val manager = mockk<TtsAudioFocusManager>()
        every { manager.requestFocus(any()) } just Runs
        every { manager.abandonFocus() } just Runs
        return manager
    }

    private fun buildEngine(
        gate: CloudTtsGate,
        cloudTtsProvider: CloudTtsProvider,
        apiKeyStore: CloudApiKeyStore,
        prefs: TtsPreferences,
        fallback: FakeTtsEngine,
        playback: FakeCloudPlayback,
        scope: CoroutineScope,
    ) = CloudTtsEngine(
        gate = gate,
        cloudTtsProvider = cloudTtsProvider,
        apiKeyStore = apiKeyStore,
        preferences = prefs,
        engines = mapOf(TtsEngineType.ANDROID to Provider { fallback }),
        scope = scope,
        audioFocusManager = fakeAudioFocusManager(),
        playback = playback,
    )

    private fun closedGate(): CloudTtsGate = mockk<CloudTtsGate>().also {
        every { it.observeCanUseCloudTts() } returns MutableStateFlow(false)
    }

    private fun openGate(): CloudTtsGate = mockk<CloudTtsGate>().also {
        every { it.observeCanUseCloudTts() } returns MutableStateFlow(true)
    }

    @Test
    fun `speak falls back to on-device engine when the gate is closed`(@TempDir tempDir: File) = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fallback = FakeTtsEngine()
        val provider = FakeCloudTtsProvider()
        val engine = buildEngine(closedGate(), provider, FakeCloudApiKeyStore(), preferences(tempDir, scope), fallback, FakeCloudPlayback(), scope)

        engine.speak("hello world")
        advanceUntilIdle()

        assertEquals("hello world", fallback.lastSpokenText)
        assertEquals(0, provider.synthesizeCallCount, "a closed gate must never reach the network")
    }

    @Test
    fun `speak falls back when no cloud provider or voice is selected`(@TempDir tempDir: File) = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fallback = FakeTtsEngine()
        val provider = FakeCloudTtsProvider()
        // Gate open, but preferences never set a selected provider/voice.
        val engine = buildEngine(openGate(), provider, FakeCloudApiKeyStore(), preferences(tempDir, scope), fallback, FakeCloudPlayback(), scope)

        engine.speak("hello")
        advanceUntilIdle()

        assertEquals("hello", fallback.lastSpokenText)
        assertEquals(0, provider.synthesizeCallCount)
    }

    @Test
    fun `speak falls back when no credentials are saved for the selected provider`(@TempDir tempDir: File) = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fallback = FakeTtsEngine()
        val provider = FakeCloudTtsProvider()
        val prefs = preferences(tempDir, scope)
        prefs.setSelectedCloudProvider(CloudProviderId.OPENAI.name)
        prefs.setSelectedVoice("alloy")
        val engine = buildEngine(openGate(), provider, FakeCloudApiKeyStore(stored = null), prefs, fallback, FakeCloudPlayback(), scope)

        engine.speak("hello")
        advanceUntilIdle()

        assertEquals("hello", fallback.lastSpokenText)
    }

    @Test
    fun `speak plays synthesized audio when the gate is open and everything is configured`(@TempDir tempDir: File) = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fallback = FakeTtsEngine()
        val provider = FakeCloudTtsProvider(result = Result.success(byteArrayOf(9, 8, 7)))
        val prefs = preferences(tempDir, scope)
        prefs.setSelectedCloudProvider(CloudProviderId.OPENAI.name)
        prefs.setSelectedVoice("alloy")
        val playback = FakeCloudPlayback()
        val engine = buildEngine(
            openGate(),
            provider,
            FakeCloudApiKeyStore(stored = mapOf(CloudCredentialFields.API_KEY to "sk-test")),
            prefs,
            fallback,
            playback,
            scope,
        )

        engine.speak("hello")
        advanceUntilIdle()

        assertTrue(playback.playedBytes?.contentEquals(byteArrayOf(9, 8, 7)) == true)
        assertNull(fallback.lastSpokenText, "must not fall back when cloud synthesis succeeds")
    }

    @Test
    fun `speak falls back when cloud synthesis fails`(@TempDir tempDir: File) = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fallback = FakeTtsEngine()
        val provider = FakeCloudTtsProvider(result = Result.failure(RuntimeException("HTTP 500")))
        val prefs = preferences(tempDir, scope)
        prefs.setSelectedCloudProvider(CloudProviderId.OPENAI.name)
        prefs.setSelectedVoice("alloy")
        val engine = buildEngine(
            openGate(),
            provider,
            FakeCloudApiKeyStore(stored = mapOf(CloudCredentialFields.API_KEY to "sk-test")),
            prefs,
            fallback,
            FakeCloudPlayback(),
            scope,
        )

        engine.speak("hello")
        advanceUntilIdle()

        assertEquals("hello", fallback.lastSpokenText)
    }

    @Test
    fun `speak falls back when playback itself errors after a successful synthesis`(@TempDir tempDir: File) = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fallback = FakeTtsEngine()
        val provider = FakeCloudTtsProvider(result = Result.success(byteArrayOf(1)))
        val prefs = preferences(tempDir, scope)
        prefs.setSelectedCloudProvider(CloudProviderId.OPENAI.name)
        prefs.setSelectedVoice("alloy")
        val erroringPlayback = object : CloudPlayback {
            override fun play(audioBytes: ByteArray, onCompletion: () -> Unit, onError: (String) -> Unit) {
                onError("decoder error")
            }
            override fun pause() {}
            override fun resume() {}
            override fun stop() {}
        }
        val engine = CloudTtsEngine(
            gate = openGate(),
            cloudTtsProvider = provider,
            apiKeyStore = FakeCloudApiKeyStore(stored = mapOf(CloudCredentialFields.API_KEY to "sk-test")),
            preferences = prefs,
            engines = mapOf(TtsEngineType.ANDROID to Provider { fallback }),
            scope = scope,
            audioFocusManager = fakeAudioFocusManager(),
            playback = erroringPlayback,
        )

        engine.speak("hello")
        advanceUntilIdle()

        assertEquals("hello", fallback.lastSpokenText)
    }

    @Test
    fun `speak continues to the next chunk only after the previous chunk finishes playing`(@TempDir tempDir: File) = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fallback = FakeTtsEngine()
        val provider = FakeCloudTtsProvider(result = Result.success(byteArrayOf(1)))
        val prefs = preferences(tempDir, scope)
        prefs.setSelectedCloudProvider(CloudProviderId.OPENAI.name)
        prefs.setSelectedVoice("alloy")
        val playback = FakeCloudPlayback()
        val engine = buildEngine(
            openGate(),
            provider,
            FakeCloudApiKeyStore(stored = mapOf(CloudCredentialFields.API_KEY to "sk-test")),
            prefs,
            fallback,
            playback,
            scope,
        )
        // Two "sentences" well past MAX_CHUNK_CHARS (3900) so splitIntoChunks
        // produces exactly two chunks.
        val longText = "Sentence one. ".repeat(400)

        engine.speak(longText)
        advanceUntilIdle()

        assertEquals(1, provider.synthesizeCallCount, "must not fetch chunk 2 before chunk 1 finishes playing")
        assertTrue(playback.playedBytes != null)

        // Simulate chunk 1's audio finishing.
        playback.onCompletion?.invoke()
        advanceUntilIdle()

        assertEquals(2, provider.synthesizeCallCount, "chunk 2 must be fetched once chunk 1's playback completes")
        assertNull(fallback.lastSpokenText)
    }

    @Test
    fun `stop while a cloud synthesis call is in flight cancels it rather than falling back`(@TempDir tempDir: File) = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fallback = FakeTtsEngine()
        val provider = SuspendingCloudTtsProvider()
        val prefs = preferences(tempDir, scope)
        prefs.setSelectedCloudProvider(CloudProviderId.OPENAI.name)
        prefs.setSelectedVoice("alloy")
        val engine = buildEngine(
            openGate(),
            provider,
            FakeCloudApiKeyStore(stored = mapOf(CloudCredentialFields.API_KEY to "sk-test")),
            prefs,
            fallback,
            FakeCloudPlayback(),
            scope,
        )

        engine.speak("hello")
        // With an UnconfinedTestDispatcher, speak() has already run eagerly
        // up to synthesize()'s awaitCancellation() suspension point here —
        // i.e. a real network call is genuinely "in flight".
        engine.stop()
        advanceUntilIdle()

        assertNull(
            fallback.lastSpokenText,
            "stop() must cancel the in-flight call, not have it look like an ordinary " +
                "synthesis failure that triggers fallback.speak() — this is exactly the bug " +
                "kotlin.runCatching's CancellationException-swallowing caused before " +
                "runCatchingCancellable fixed it",
        )
    }
}
