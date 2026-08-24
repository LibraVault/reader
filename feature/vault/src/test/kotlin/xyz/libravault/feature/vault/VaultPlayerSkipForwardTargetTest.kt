package xyz.libravault.feature.vault

import androidx.media3.common.C
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regression guard: [skipForwardTarget] used to clamp the skip target against
 * `exo.duration.coerceAtLeast(0)`, which collapsed the target to 0 (the very
 * start of the book) whenever the duration wasn't known yet
 * ([C.TIME_UNSET] is negative), instead of leaving the skip a plain forward
 * nudge.
 */
class VaultPlayerSkipForwardTargetTest {

    @Test
    fun `skips forward by the full amount when duration is unknown`() {
        assertEquals(30_000L, skipForwardTarget(currentPositionMs = 0L, durationMs = C.TIME_UNSET, skipMs = 30_000L))
        assertEquals(45_000L, skipForwardTarget(currentPositionMs = 15_000L, durationMs = C.TIME_UNSET, skipMs = 30_000L))
    }

    @Test
    fun `clamps to the known duration`() {
        assertEquals(100_000L, skipForwardTarget(currentPositionMs = 90_000L, durationMs = 100_000L, skipMs = 30_000L))
    }

    @Test
    fun `never seeks to a negative position`() {
        assertEquals(0L, skipForwardTarget(currentPositionMs = -10_000L, durationMs = C.TIME_UNSET, skipMs = 5_000L))
    }
}
