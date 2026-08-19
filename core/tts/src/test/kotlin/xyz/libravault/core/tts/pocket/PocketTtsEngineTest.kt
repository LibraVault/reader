package xyz.libravault.core.tts.pocket

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.libravault.core.tts.TtsAudioFocusManager
import xyz.libravault.core.tts.TtsStatus
import xyz.libravault.core.tts.TtsVoiceInfo

/**
 * Covers [PocketTtsEngine]'s state machine around [PocketModelManager] and
 * the pure playback-control delegation. What it deliberately does NOT cover:
 * the [ModelStatus.Ready] path all the way through constructing a real
 * sherpa-onnx `OfflineTts` - that calls `System.loadLibrary`, which needs a
 * real Android device/emulator and crashes with `UnsatisfiedLinkError` (an
 * `Error`, not an `Exception` - `initialize()`'s own try/catch can't even
 * contain it) on a plain JVM unit test. Same documented-gap convention as
 * [xyz.libravault.core.tts.AndroidTtsEngineTest] around the real
 * `android.speech.tts.TextToSpeech` boundary.
 *
 * Every test runs under `runTest(UnconfinedTestDispatcher())` and passes the
 * `TestScope` itself as the injected [CoroutineScope], so `initialize()`'s
 * `scope.launch { modelManager.ensureModelAvailable().collect { ... } }` is
 * driven by the same scheduler the test body runs on - see
 * `TtsEngineProviderTest`'s KDoc for why a bare
 * `CoroutineScope(UnconfinedTestDispatcher())` built outside `runTest` isn't
 * reliable once more than one collector/launch is involved.
 */
class PocketTtsEngineTest {

    private val audioFocusManager = mockk<TtsAudioFocusManager>(relaxed = true)

    private fun CoroutineScope.engine(modelManager: PocketModelManager) =
        PocketTtsEngine(modelManager, this, audioFocusManager)

    @Test
    fun `PocketTtsEngine implements TtsEngine interface`() = runTest(UnconfinedTestDispatcher()) {
        val engine = engine(mockk(relaxed = true))
        assertNotNull(engine.state)
        assertNotNull(engine.completionEvent)
        assertNotNull(engine.stopEvent)
    }

    @Test
    fun `PocketTtsEngine has empty initial voices`() = runTest(UnconfinedTestDispatcher()) {
        val engine = engine(mockk(relaxed = true))
        assertEquals(0, engine.state.value.availableVoices.size)
        assertEquals(TtsStatus.UNINITIALIZED, engine.state.value.status)
    }

    @Test
    fun `TtsVoiceInfo is constructible for pocket voices`() {
        val voice = TtsVoiceInfo(
            id = "pocket-voice-1",
            displayName = "Pocket Voice 1",
            locale = "en-US",
            requiresNetwork = false,
        )
        assertEquals("pocket-voice-1", voice.id)
        assertEquals(false, voice.requiresNetwork)
    }

    // ── initialize() against PocketModelManager's model status ──────────────

    @Test
    fun `initialize stays INITIALIZING while the model is idle or preparing`() = runTest(UnconfinedTestDispatcher()) {
        val modelManager = mockk<PocketModelManager> {
            every { ensureModelAvailable() } returns flowOf(
                ModelStatus.Idle,
                ModelStatus.Preparing(0.5f),
            )
        }
        val engine = engine(modelManager)

        engine.initialize()

        assertEquals(TtsStatus.INITIALIZING, engine.state.value.status)
        assertEquals(0, engine.state.value.availableVoices.size)
    }

    @Test
    fun `initialize surfaces a model setup failure as an ERROR state`() = runTest(UnconfinedTestDispatcher()) {
        val modelManager = mockk<PocketModelManager> {
            every { ensureModelAvailable() } returns flowOf(ModelStatus.Failed("checksum mismatch"))
        }
        val engine = engine(modelManager)

        engine.initialize()

        assertEquals(TtsStatus.ERROR, engine.state.value.status)
        assertEquals("checksum mismatch", engine.state.value.error)
    }

    @Test
    fun `initialize is a no-op while already initializing`() = runTest(UnconfinedTestDispatcher()) {
        // ensureModelAvailable() emits nothing further, so the engine is
        // left in INITIALIZING - a second initialize() call must not relaunch
        // the collector (the status guard is what prevents that).
        val modelManager = mockk<PocketModelManager> {
            every { ensureModelAvailable() } returns flowOf(ModelStatus.Idle)
        }
        val engine = engine(modelManager)

        engine.initialize()
        engine.initialize()

        verify(exactly = 1) { modelManager.ensureModelAvailable() }
    }

    // ── speak() guards ───────────────────────────────────────────────────────

    @Test
    fun `speak before initialization is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val engine = engine(mockk(relaxed = true))

        engine.speak("hello")

        assertEquals(TtsStatus.UNINITIALIZED, engine.state.value.status)
    }

    @Test
    fun `speak while still initializing is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val modelManager = mockk<PocketModelManager> {
            every { ensureModelAvailable() } returns flowOf(ModelStatus.Idle)
        }
        val engine = engine(modelManager)
        engine.initialize()

        engine.speak("hello")

        assertEquals(TtsStatus.INITIALIZING, engine.state.value.status)
    }

    // ── playback control delegation (no live tts instance needed) ──────────

    @Test
    fun `pause updates status to PAUSED`() = runTest(UnconfinedTestDispatcher()) {
        val engine = engine(mockk(relaxed = true))
        engine.pause()
        assertEquals(TtsStatus.PAUSED, engine.state.value.status)
    }

    @Test
    fun `resume is a no-op unless currently PAUSED`() = runTest(UnconfinedTestDispatcher()) {
        val engine = engine(mockk(relaxed = true))
        engine.resume()
        assertEquals(TtsStatus.UNINITIALIZED, engine.state.value.status)
    }

    @Test
    fun `resume after pause moves to PLAYING`() = runTest(UnconfinedTestDispatcher()) {
        val engine = engine(mockk(relaxed = true))
        engine.pause()
        engine.resume()
        assertEquals(TtsStatus.PLAYING, engine.state.value.status)
    }

    @Test
    fun `stop resets status to IDLE`() = runTest(UnconfinedTestDispatcher()) {
        val engine = engine(mockk(relaxed = true))
        engine.pause()
        engine.stop()
        assertEquals(TtsStatus.IDLE, engine.state.value.status)
    }

    @Test
    fun `stop emits stopEvent`() = runTest(UnconfinedTestDispatcher()) {
        val engine = engine(mockk(relaxed = true))

        // CoroutineStart.UNDISPATCHED runs this coroutine inline up to its first
        // suspension point (registering the collection on stopEvent) before this
        // line returns, so stop()'s tryEmit below is guaranteed to have a live
        // subscriber - a SharedFlow with no replay drops emissions nobody is
        // collecting for yet.
        val stopEventDeferred = async(start = CoroutineStart.UNDISPATCHED) { engine.stopEvent.first() }

        engine.stop()

        withTimeout(1_000) { stopEventDeferred.await() }
    }

    // ── Audio focus (#137 mutual exclusion via lockscreen/notification) ────

    @Test
    fun `pause abandons audio focus`() = runTest(UnconfinedTestDispatcher()) {
        val engine = engine(mockk(relaxed = true))
        engine.pause()
        verify { audioFocusManager.abandonFocus() }
    }

    @Test
    fun `resume after pause requests audio focus`() = runTest(UnconfinedTestDispatcher()) {
        val engine = engine(mockk(relaxed = true))
        engine.pause()
        engine.resume()
        verify { audioFocusManager.requestFocus(any()) }
    }

    @Test
    fun `resume while not paused does not request audio focus`() = runTest(UnconfinedTestDispatcher()) {
        val engine = engine(mockk(relaxed = true))
        engine.resume()
        verify(exactly = 0) { audioFocusManager.requestFocus(any()) }
    }

    @Test
    fun `stop abandons audio focus`() = runTest(UnconfinedTestDispatcher()) {
        val engine = engine(mockk(relaxed = true))
        engine.stop()
        verify { audioFocusManager.abandonFocus() }
    }

    @Test
    fun `losing audio focus while resumed stops the engine`() = runTest(UnconfinedTestDispatcher()) {
        val onFocusLostSlot = io.mockk.slot<() -> Unit>()
        every { audioFocusManager.requestFocus(capture(onFocusLostSlot)) } returns Unit
        val engine = engine(mockk(relaxed = true))
        engine.pause()
        engine.resume()

        onFocusLostSlot.captured.invoke()

        assertEquals(TtsStatus.IDLE, engine.state.value.status)
    }

    @Test
    fun `losing audio focus while resumed emits stopEvent`() = runTest(UnconfinedTestDispatcher()) {
        // #280/#281 - this is the exact path TtsAudioFocusManager uses to stop TTS
        // out from under an active Read Aloud session without going through the
        // ViewModel's stopReadAloud(). stopEvent is what lets a caller detect that.
        val onFocusLostSlot = io.mockk.slot<() -> Unit>()
        every { audioFocusManager.requestFocus(capture(onFocusLostSlot)) } returns Unit
        val engine = engine(mockk(relaxed = true))
        engine.pause()
        engine.resume()
        // See the CoroutineStart.UNDISPATCHED comment on `stop emits stopEvent` above.
        val stopEventDeferred = async(start = CoroutineStart.UNDISPATCHED) { engine.stopEvent.first() }

        onFocusLostSlot.captured.invoke()

        withTimeout(1_000) { stopEventDeferred.await() }
    }

    @Test
    fun `shutdown abandons audio focus`() = runTest(UnconfinedTestDispatcher()) {
        val engine = engine(mockk(relaxed = true))
        engine.shutdown()
        verify { audioFocusManager.abandonFocus() }
    }

    @Test
    fun `setVoice with an unknown id before initialization is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val engine = engine(mockk(relaxed = true))
        engine.setVoice("unknown-voice")
        assertNull(engine.state.value.selectedVoiceId)
    }

    @Test
    fun `setSpeechRate updates state regardless of status`() = runTest(UnconfinedTestDispatcher()) {
        val engine = engine(mockk(relaxed = true))
        engine.setSpeechRate(1.5f)
        assertEquals(1.5f, engine.state.value.speechRate)
    }

    @Test
    fun `shutdown resets state and does not crash without a live tts instance`() = runTest(UnconfinedTestDispatcher()) {
        val engine = engine(mockk(relaxed = true))
        engine.setSpeechRate(2.0f)

        engine.shutdown()

        assertEquals(TtsStatus.UNINITIALIZED, engine.state.value.status)
        assertEquals(1.0f, engine.state.value.speechRate, "shutdown() replaces state with a fresh TtsState()")
        assertTrue(engine.state.value.availableVoices.isEmpty())
    }
}
