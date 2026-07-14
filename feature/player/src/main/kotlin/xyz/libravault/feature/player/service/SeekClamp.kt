package xyz.libravault.feature.player.service

import androidx.media3.common.C

/**
 * Pure helpers for the ±seek button path in `LibraryViewModel.seekBack/seekForward`
 * and `ReaderViewModel.seekBackAudiobook/seekForwardAudiobook`.
 *
 * Extracted so the clamp math (especially the `C.TIME_UNSET` case the third
 * review pass flagged) is unit-testable without standing up a real
 * `MediaController` / Robolectric.
 */
object SeekClamp {
    /**
     * Returns `currentPosition + deltaMs`, clamped to the half-open interval
     * `[0, duration]`. Treats [C.TIME_UNSET] as "no upper bound" — naive
     * `coerceAtLeast(0L)` on that sentinel collapses to 0, which would snap
     * every ±seek tap to the start while the player is buffering.
     */
    fun clamp(currentPosition: Long, deltaMs: Long, duration: Long): Long {
        val maxPos = if (duration == C.TIME_UNSET) Long.MAX_VALUE else duration.coerceAtLeast(0L)
        return (currentPosition + deltaMs).coerceIn(0L, maxPos)
    }
}
