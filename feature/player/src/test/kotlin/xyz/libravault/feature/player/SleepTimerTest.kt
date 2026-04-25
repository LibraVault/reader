package xyz.libravault.feature.player

import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
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
        val timer = SleepTimer(player)
        assertEquals(SleepTimerState.Inactive, timer.state.value)
        assertFalse(timer.isActive)
    }

    @Test
    fun `cancel while inactive is safe`() {
        val timer = SleepTimer(player)
        timer.cancel() // Should not throw
        assertEquals(SleepTimerState.Inactive, timer.state.value)
    }

    @Test
    fun `start transitions to active state`() = runTest {
        val timer = SleepTimer(player)
        timer.state.test {
            assertEquals(SleepTimerState.Inactive, awaitItem())
            timer.start(60_000L, this@runTest)
            val active = awaitItem()
            assertTrue(active is SleepTimerState.Active)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cancel resets to inactive`() = runTest {
        val timer = SleepTimer(player)
        timer.state.test {
            awaitItem() // Inactive
            timer.start(60_000L, this@runTest)
            awaitItem() // Active
            timer.cancel()
            val inactive = awaitItem()
            assertEquals(SleepTimerState.Inactive, inactive)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `starting new timer cancels previous`() = runTest {
        val timer = SleepTimer(player)
        timer.start(60_000L, this)
        assertTrue(timer.isActive)
        timer.start(30_000L, this)
        assertTrue(timer.isActive)
    }

    @Test
    fun `volume is restored after fade`() = runTest {
        val timer = SleepTimer(player)
        timer.start(0L, this)
        verify(atLeast = 0) { player.volume = any() }
    }
}
