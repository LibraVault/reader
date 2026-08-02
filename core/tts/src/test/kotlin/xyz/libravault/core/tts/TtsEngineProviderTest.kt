package xyz.libravault.core.tts

import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Covers [TtsEngineProvider]'s reactive engine-switching orchestration.
 *
 * Made possible by injecting the [CoroutineScope] (previously a hardcoded
 * `CoroutineScope(Dispatchers.Default)` internal field) - a real background
 * dispatcher can't be driven deterministically from a JVM unit test. Deferred
 * from PR #74 for exactly this reason - this is the fix that unblocked it.
 *
 * Each test runs under `runTest(UnconfinedTestDispatcher())` and passes the
 * `TestScope` itself (via `this`) as the injected scope, so `init{}`'s
 * `scope.launch { ... }` collectors are driven by the same test scheduler
 * the test body runs on - a bare `CoroutineScope(UnconfinedTestDispatcher())`
 * built outside `runTest` does NOT reliably pump launched coroutines to
 * completion before assertions run.
 */
class TtsEngineProviderTest {

    @BeforeEach
    fun mockAndroidLog() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    private val androidEngine = mockk<TtsEngine>(relaxed = true)
    private val pocketEngine = mockk<TtsEngine>(relaxed = true)
    private val factory = mockk<TtsEngineFactory> {
        every { create(TtsEngineType.ANDROID) } returns androidEngine
        every { create(TtsEngineType.POCKET_TTS) } returns pocketEngine
    }

    private fun CoroutineScope.provider(
        engineTypeFlow: Flow<TtsEngineType> = flowOf(TtsEngineType.ANDROID),
        selectedVoiceFlow: Flow<String?> = flowOf(null),
    ): TtsEngineProvider {
        val preferences = mockk<TtsPreferences> {
            every { this@mockk.engineTypeFlow } returns engineTypeFlow
            every { this@mockk.selectedVoiceFlow } returns selectedVoiceFlow
        }
        return TtsEngineProvider(factory, preferences, this)
    }

    @Test
    fun `constructing initializes and exposes the Android engine by default`() = runTest(UnconfinedTestDispatcher()) {
        val provider = provider()

        assertSame(androidEngine, provider.engine.value)
        assertEquals(TtsEngineType.ANDROID, provider.engineType.value)
        verify { androidEngine.initialize() }
    }

    @Test
    fun `emitting the already-current engine type is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        // engineTypeFlow emits ANDROID, which is already the initial type -
        // the dedup guard (_engineType.value == newType) must prevent a
        // redundant stop+create+initialize cycle.
        provider(engineTypeFlow = flowOf(TtsEngineType.ANDROID))

        verify(exactly = 1) { factory.create(TtsEngineType.ANDROID) }
        verify(exactly = 1) { androidEngine.initialize() }
        verify(exactly = 0) { androidEngine.stop() }
        verify(exactly = 0) { factory.create(TtsEngineType.POCKET_TTS) }
    }

    @Test
    fun `switching engine type stops the old engine before creating and initializing the new one`() = runTest(UnconfinedTestDispatcher()) {
        val provider = provider(engineTypeFlow = flowOf(TtsEngineType.POCKET_TTS))

        assertSame(pocketEngine, provider.engine.value)
        assertEquals(TtsEngineType.POCKET_TTS, provider.engineType.value)
        verifyOrder {
            androidEngine.stop()
            factory.create(TtsEngineType.POCKET_TTS)
            pocketEngine.initialize()
        }
    }

    @Test
    fun `switching back and forth only reacts to actual changes`() = runTest(UnconfinedTestDispatcher()) {
        // ANDROID (initial, deduped) -> POCKET_TTS (real switch) -> POCKET_TTS (deduped again)
        provider(engineTypeFlow = flowOf(TtsEngineType.ANDROID, TtsEngineType.POCKET_TTS, TtsEngineType.POCKET_TTS))

        verify(exactly = 1) { factory.create(TtsEngineType.POCKET_TTS) }
        verify(exactly = 1) { pocketEngine.initialize() }
        verify(exactly = 1) { androidEngine.stop() }
    }

    @Test
    fun `a non-null voice selection is applied to the current engine`() = runTest(UnconfinedTestDispatcher()) {
        provider(selectedVoiceFlow = flowOf("voice-123"))

        verify { androidEngine.setVoice("voice-123") }
    }

    @Test
    fun `a null voice selection is not applied`() = runTest(UnconfinedTestDispatcher()) {
        provider(selectedVoiceFlow = flowOf(null))

        verify(exactly = 0) { androidEngine.setVoice(any()) }
    }

    @Test
    fun `switchEngineSync drives the same switch logic as the preferences flow`() = runTest(UnconfinedTestDispatcher()) {
        val provider = provider()

        provider.switchEngineSync(TtsEngineType.POCKET_TTS)

        assertSame(pocketEngine, provider.engine.value)
        assertEquals(TtsEngineType.POCKET_TTS, provider.engineType.value)
        verify { androidEngine.stop() }
        verify { pocketEngine.initialize() }
    }

    @Test
    fun `shutdown delegates to the current engine`() = runTest(UnconfinedTestDispatcher()) {
        val provider = provider()

        provider.shutdown()

        verify { androidEngine.shutdown() }
    }
}
