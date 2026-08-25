package xyz.libravault.feature.reader

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Bounded [ScrollableState] fake — position clamped to `[0, maxValue]`, and
 * [ScrollScope.scrollBy] returns only what's actually consumed (partial once near
 * the bound), the same contract real `ScrollState`/`LazyListState` implementations
 * have. Exercises [autoScroll]'s own logic without a real Compose runtime — this
 * module has no Robolectric/Compose-UI-test coverage for plain suspend functions
 * like this one, and none is needed since [ScrollableState] is a plain interface.
 */
private class FakeScrollableState(private val maxValue: Float) : ScrollableState {
    var position = 0f
        private set

    /** When true, the next [ScrollScope.scrollBy] call throws instead of scrolling —
     *  simulates a higher-priority user gesture preempting this tick. */
    var preemptNextScroll = false

    override suspend fun scroll(
        scrollPriority: MutatePriority,
        block: suspend ScrollScope.() -> Unit,
    ) {
        val scope = object : ScrollScope {
            override fun scrollBy(pixels: Float): Float {
                if (preemptNextScroll) {
                    preemptNextScroll = false
                    throw CancellationException("preempted by a higher priority gesture")
                }
                val consumed = pixels.coerceAtMost((maxValue - position).coerceAtLeast(0f))
                position += consumed
                return consumed
            }
        }
        scope.block()
    }

    override fun dispatchRawDelta(delta: Float): Float = delta
    override val isScrollInProgress: Boolean = false
}

class AutoScrollTest {

    @Test
    fun `autoScroll advances position over several ticks`() = runTest {
        val state = FakeScrollableState(maxValue = 100_000f)
        var finished = false

        val job = launch { state.autoScroll(speed = 1.0f) { finished = true } }
        advanceTimeBy(AUTO_SCROLL_TICK_MS * 5)
        job.cancel()

        assertTrue(state.position > 0f, "expected position to have advanced, was ${state.position}")
        assertFalse(finished, "onFinished should not fire while there's still room to scroll")
    }

    @Test
    fun `autoScroll calls onFinished and stops once it reaches the end of content`() = runTest {
        // A tiny scrollable range — one tick's worth of pixels at 1.0x is already
        // more than this, so the very first scrollBy() call hits the bound and
        // consumes less than half of what was requested.
        val state = FakeScrollableState(maxValue = 1f)
        var finished = false

        state.autoScroll(speed = 1.0f) { finished = true }

        assertTrue(finished, "onFinished should fire once the end of content is reached")
        assertEquals(1f, state.position)
    }

    @Test
    fun `autoScroll calls onFinished and stops when a user gesture preempts a tick`() = runTest {
        val state = FakeScrollableState(maxValue = 100_000f)
        state.preemptNextScroll = true
        var finished = false

        state.autoScroll(speed = 1.0f) { finished = true }

        assertTrue(finished, "onFinished should fire when a manual gesture preempts a scroll tick")
        assertEquals(0f, state.position, "no distance should be recorded from a preempted tick")
    }

    @Test
    fun `autoScroll is a no-op for a non-positive speed`() = runTest {
        val state = FakeScrollableState(maxValue = 100_000f)
        var finished = false

        state.autoScroll(speed = 0f) { finished = true }

        assertEquals(0f, state.position)
        assertFalse(finished)
    }

    @Test
    fun `autoAdvancePages calls advancePage repeatedly until it returns false`() = runTest {
        var pageCalls = 0
        var finished = false

        autoAdvancePages(speed = 1.0f, onFinished = { finished = true }) {
            pageCalls++
            pageCalls < 3
        }

        assertEquals(3, pageCalls)
        assertTrue(finished)
    }

    @Test
    fun `autoAdvancePages waits longer between turns at a slower speed`() = runTest {
        var pageCalls = 0

        autoAdvancePages(speed = 0.5f, onFinished = {}) {
            pageCalls++
            pageCalls < 2
        }

        // Two turns at half speed = 2 * (baseline / 0.5) = 4x the baseline interval.
        assertEquals(AUTO_SCROLL_BASE_PAGE_INTERVAL_MS * 4, currentTime)
    }

    @Test
    fun `autoAdvancePages is a no-op for a non-positive speed`() = runTest {
        var pageCalls = 0
        autoAdvancePages(speed = 0f, onFinished = {}) { pageCalls++; true }
        assertEquals(0, pageCalls)
    }
}
