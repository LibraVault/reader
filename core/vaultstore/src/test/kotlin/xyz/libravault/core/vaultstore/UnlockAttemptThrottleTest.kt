package xyz.libravault.core.vaultstore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnlockAttemptThrottleTest {

    @Test
    fun `first few failures are never throttled`() {
        repeat(3) { n ->
            assertFalse(UnlockAttemptThrottle.isThrottled(n, lastAttemptEpochMillis = 0L, nowEpochMillis = 0L))
        }
    }

    @Test
    fun `throttled immediately after exceeding the free-attempt threshold`() {
        assertTrue(UnlockAttemptThrottle.isThrottled(4, lastAttemptEpochMillis = 1_000L, nowEpochMillis = 1_000L))
    }

    @Test
    fun `no longer throttled once enough time has passed`() {
        val delay = UnlockAttemptThrottle.remainingDelayMillis(4, lastAttemptEpochMillis = 0L, nowEpochMillis = 0L)
        assertTrue(delay > 0)
        assertFalse(UnlockAttemptThrottle.isThrottled(4, lastAttemptEpochMillis = 0L, nowEpochMillis = delay))
    }

    @Test
    fun `delay increases with more consecutive failures`() {
        val d1 = UnlockAttemptThrottle.remainingDelayMillis(4, 0L, 0L)
        val d2 = UnlockAttemptThrottle.remainingDelayMillis(6, 0L, 0L)
        val d3 = UnlockAttemptThrottle.remainingDelayMillis(10, 0L, 0L)
        assertTrue(d2 > d1, "expected delay to grow: d1=$d1 d2=$d2")
        assertTrue(d3 > d2, "expected delay to grow: d2=$d2 d3=$d3")
    }

    @Test
    fun `delay is capped, does not grow unbounded`() {
        val d100 = UnlockAttemptThrottle.remainingDelayMillis(100, 0L, 0L)
        val d1000 = UnlockAttemptThrottle.remainingDelayMillis(1000, 0L, 0L)
        assertEquals(d100, d1000, "delay should be capped once it hits the maximum")
    }

    @Test
    fun `never returns a negative delay`() {
        val delay = UnlockAttemptThrottle.remainingDelayMillis(10, lastAttemptEpochMillis = 0L, nowEpochMillis = Long.MAX_VALUE / 2)
        assertTrue(delay >= 0)
    }
}
