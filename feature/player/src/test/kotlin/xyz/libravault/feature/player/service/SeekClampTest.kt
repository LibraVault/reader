package xyz.libravault.feature.player.service

import androidx.media3.common.C
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SeekClamp.clamp] — the pure clamp math used by
 * `LibraryViewModel.seekBy` and `ReaderViewModel.seekByAudiobook`.
 *
 * Covers the three boundary cases the third review pass asked for:
 *  - currentPosition near 0 with a negative delta,
 *  - currentPosition near duration with a positive delta,
 *  - duration reporting `C.TIME_UNSET` (buffering / no timeline).
 */
class SeekClampTest {

    @Test
    fun `clamp normal case - position in middle, delta inside range`() {
        assertEquals(45_000L, SeekClamp.clamp(currentPosition = 30_000L, deltaMs =  15_000L, duration = 60_000L))
        assertEquals(15_000L, SeekClamp.clamp(currentPosition = 30_000L, deltaMs = -15_000L, duration = 60_000L))
    }

    @Test
    fun `clamp near 0 with negative delta - does not seek below 0`() {
        assertEquals(0L, SeekClamp.clamp(currentPosition = 5_000L, deltaMs = -30_000L, duration = 60_000L))
        assertEquals(0L, SeekClamp.clamp(currentPosition = 0L,    deltaMs = -30_000L, duration = 60_000L))
    }

    @Test
    fun `clamp near duration with positive delta - does not seek past end`() {
        assertEquals(60_000L, SeekClamp.clamp(currentPosition = 50_000L, deltaMs = 30_000L, duration = 60_000L))
        assertEquals(60_000L, SeekClamp.clamp(currentPosition = 60_000L, deltaMs =  1_000L, duration = 60_000L))
    }

    @Test
    fun `clamp with TIME_UNSET duration treats max as Long_MAX_VALUE`() {
        // The whole point of this code path: while buffering, ctrl.duration reports
        // C.TIME_UNSET (a large negative sentinel), and naive `coerceAtLeast(0L)`
        // would snap every seek to position 0. We want +15s at position 0 to yield
        // 15s, not 0s.
        assertEquals(15_000L,  SeekClamp.clamp(currentPosition = 0L,      deltaMs =  15_000L, duration = C.TIME_UNSET))
        assertEquals(1_000_000L, SeekClamp.clamp(currentPosition = 985_000L, deltaMs = 15_000L, duration = C.TIME_UNSET))
    }

    @Test
    fun `clamp with non-UNSET negative duration coerces duration to 0 - position 0 only`() {
        // Defensive: if a buggy upstream ever reports a negative non-UNSET duration
        // (distinct from C.TIME_UNSET), we coerce the upper bound to 0 rather than
        // treat it as "no upper bound" — the seek target collapses to [0, 0].
        assertEquals(0L, SeekClamp.clamp(currentPosition = 0L, deltaMs = 15_000L, duration = -5L))
    }

    @Test
    fun `clamp with zero duration allows only position 0`() {
        // Edge case: a 0-duration item. The seek target is constrained to [0, 0].
        assertEquals(0L, SeekClamp.clamp(currentPosition = 0L, deltaMs =  15_000L, duration = 0L))
        assertEquals(0L, SeekClamp.clamp(currentPosition = 0L, deltaMs = -15_000L, duration = 0L))
    }
}
