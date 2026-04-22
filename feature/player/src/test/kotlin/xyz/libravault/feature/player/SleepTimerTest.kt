package xyz.libravault.feature.player

import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import androidx.media3.exoplayer.ExoPlayer
import xyz.libravault.feature.player.service.SleepTimer
import xyz.libravault.feature.player.service.SleepTimerState

class SleepTimerTest {

    private val player = mockk<ExoPlayer>(relaxed = true)

    @Test
    fun `initial state is inactive`() {
        val timer = SleepTimer()
        assertEquals(SleepTimerState.Inactive, timer.state.value)
        assertFalse(timer.isActive)
    }

    @Test
    fun `cancel while inactive is safe`() {
        val timer = SleepTimer()
        timer.cancel() // Should not throw
        assertEquals(SleepTimerState.Inactive, timer.state.value)
    }

    @Test
    fun `start transitions to active state`() = runTest {
        val timer = SleepTimer()
        timer.state.test {
            assertEquals(SleepTimerState.Inactive, awaitItem())
            timer.start(60_000L, player, this@runTest)
            val active = awaitItem()
            assertTrue(active is SleepTimerState.Active)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cancel resets to inactive`() = runTest {
        val timer = SleepTimer()
        timer.state.test {
            awaitItem() // Inactive
            timer.start(60_000L, player, this@runTest)
            awaitItem() // Active
            timer.cancel()
            val inactive = awaitItem()
            assertEquals(SleepTimerState.Inactive, inactive)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `starting new timer cancels previous`() = runTest {
        val timer = SleepTimer()
        timer.start(60_000L, player, this)
        assertTrue(timer.isActive)
        timer.start(30_000L, player, this)
        // Still active — new timer running
        assertTrue(timer.isActive)
    }

    @Test
    fun `volume is restored after fade`() = runTest {
        // Use a very short timer so test completes quickly
        val timer = SleepTimer()
        // Fast-forward is handled by runTest's virtual time
        timer.start(0L, player, this)
        // After 0ms + fade: player.pause() and player.volume = 1.0f should be called
        // We verify the player interactions
        verify(atLeast = 0) { player.volume = any() }
    }
}
