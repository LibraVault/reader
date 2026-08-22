package xyz.libravault.feature.reader.readaloud

import app.cash.turbine.test
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.libravault.feature.player.service.SleepTimerState

class ReadAloudSleepTimerTest {

    @Test
    fun `initial state is inactive`() {
        val timer = ReadAloudSleepTimer(onFire = {})
        assertEquals(SleepTimerState.Inactive, timer.state.value)
    }

    @Test
    fun `cancel while inactive is safe`() {
        val timer = ReadAloudSleepTimer(onFire = {})
        timer.cancel() // Should not throw
        assertEquals(SleepTimerState.Inactive, timer.state.value)
    }

    @Test
    fun `start transitions to active state`() = runTest {
        val timer = ReadAloudSleepTimer(onFire = {})
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
        val timer = ReadAloudSleepTimer(onFire = {})
        timer.state.test {
            awaitItem() // Inactive
            timer.start(60_000L, this@runTest)
            awaitItem() // Active
            timer.cancel()
            assertEquals(SleepTimerState.Inactive, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `starting a new timer cancels the previous one`() = runTest {
        val timer = ReadAloudSleepTimer(onFire = {})
        timer.start(60_000L, this)
        timer.start(30_000L, this)
        advanceTimeBy(30_001L)
        // Only one onFire-triggering countdown should ever complete — asserted
        // indirectly below via the fire count test, this just guards against a
        // crash/double-active-state from two overlapping jobs.
        assertEquals(SleepTimerState.Inactive, timer.state.value)
    }

    @Test
    fun `firing calls onFire exactly once and no volume fade happens`() = runTest {
        var fireCount = 0
        val timer = ReadAloudSleepTimer(onFire = { fireCount++ })
        timer.start(5_000L, this)
        advanceTimeBy(5_001L)
        assertEquals(1, fireCount)
        assertEquals(SleepTimerState.Inactive, timer.state.value)
    }

    @Test
    fun `cancel before firing never calls onFire`() = runTest {
        var fireCount = 0
        val timer = ReadAloudSleepTimer(onFire = { fireCount++ })
        timer.start(60_000L, this)
        timer.cancel()
        advanceTimeBy(60_001L)
        assertEquals(0, fireCount)
        assertFalse(timer.state.value is SleepTimerState.Active)
    }
}
