package xyz.libravault.feature.reader.tts

import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.libravault.core.tts.TtsEngine
import xyz.libravault.core.tts.TtsState
import xyz.libravault.core.tts.TtsStatus

class TtsViewModelTest {

    private val engineState = MutableStateFlow(TtsState())
    private val engine = mockk<TtsEngine>(relaxed = true) {
        every { state } returns engineState
    }

    private fun viewModel() = TtsViewModel(engine)

    @BeforeEach fun setUp()    { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterEach  fun tearDown() { Dispatchers.resetMain() }

    // ── Initialization ─────────────────────────────────────────────────────────

    @Test
    fun `initializeIfNeeded calls engine when uninitialized`() {
        engineState.value = TtsState(status = TtsStatus.UNINITIALIZED)
        viewModel().initializeIfNeeded()
        verify(exactly = 1) { engine.initialize() }
    }

    @Test
    fun `initializeIfNeeded does not call engine when already idle`() {
        engineState.value = TtsState(status = TtsStatus.IDLE)
        viewModel().initializeIfNeeded()
        verify(exactly = 0) { engine.initialize() }
    }

    // ── Play ───────────────────────────────────────────────────────────────────

    @Test
    fun `play calls engine speak with staged text when idle`() {
        engineState.value = TtsState(status = TtsStatus.IDLE)
        val vm = viewModel()
        vm.setContent("Chapter one text.")
        vm.play()
        verify { engine.speak("Chapter one text.") }
    }

    @Test
    fun `play calls engine resume when paused`() {
        engineState.value = TtsState(status = TtsStatus.PAUSED)
        viewModel().play()
        verify { engine.resume() }
        verify(exactly = 0) { engine.speak(any()) }
    }

    @Test
    fun `play does nothing when already playing`() {
        engineState.value = TtsState(status = TtsStatus.PLAYING)
        viewModel().play()
        verify(exactly = 0) { engine.speak(any()) }
        verify(exactly = 0) { engine.resume() }
    }

    @Test
    fun `play does not call speak when content is blank`() {
        engineState.value = TtsState(status = TtsStatus.IDLE)
        val vm = viewModel()
        vm.setContent("   ")
        vm.play()
        verify(exactly = 0) { engine.speak(any()) }
    }

    // ── Pause / Stop ───────────────────────────────────────────────────────────

    @Test
    fun `pause delegates to engine`() {
        viewModel().pause()
        verify { engine.pause() }
    }

    @Test
    fun `stop delegates to engine`() {
        viewModel().stop()
        verify { engine.stop() }
    }

    // ── Voice / rate ───────────────────────────────────────────────────────────

    @Test
    fun `setVoice delegates to engine`() {
        viewModel().setVoice("en-us-x-iol-local")
        verify { engine.setVoice("en-us-x-iol-local") }
    }

    @Test
    fun `setSpeechRate clamps to 0_5 minimum`() {
        viewModel().setSpeechRate(0.1f)
        verify { engine.setSpeechRate(0.5f) }
    }

    @Test
    fun `setSpeechRate clamps to 3_0 maximum`() {
        viewModel().setSpeechRate(5.0f)
        verify { engine.setSpeechRate(3.0f) }
    }

    @Test
    fun `setSpeechRate passes through valid value`() {
        viewModel().setSpeechRate(1.5f)
        verify { engine.setSpeechRate(1.5f) }
    }

    // ── State forwarding ───────────────────────────────────────────────────────

    @Test
    fun `state flow reflects engine state`() = runTest {
        val vm = viewModel()
        vm.state.test {
            assertEquals(TtsStatus.UNINITIALIZED, awaitItem().status)

            engineState.value = TtsState(status = TtsStatus.PLAYING)
            assertEquals(TtsStatus.PLAYING, awaitItem().status)

            cancelAndIgnoreRemainingEvents()
        }
    }

}
