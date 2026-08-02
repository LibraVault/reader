package xyz.libravault.feature.player.service

/**
 * String constants for custom [androidx.media3.session.SessionCommand]s understood by
 * [LibravaultMediaCallback].
 *
 * Wired into [LibravaultMediaCallback.buildStandardStrip] (which publishes the lockscreen
 * / Quick-Settings media tile strip) and into [LibravaultMediaCallback.dispatch]
 * (which routes them to the underlying [androidx.media3.exoplayer.ExoPlayer]).
 *
 * Why custom commands (and not [androidx.media3.common.Player.COMMAND_SEEK_BACK] etc.):
 * the legacy `PlaybackStateCompat` that feeds the Android 13+ system media tile only
 * receives [androidx.media3.session.CommandButton]s whose `sessionCommand` has
 * `commandCode == COMMAND_CODE_CUSTOM` — see `PlayerWrapper.createPlaybackStateCompat`
 * in Media3 1.3.1 (filter verified via `javap` of the AAR). Player-command buttons are
 * filtered out before they reach the platform notification, so they never appear on the
 * system tile. Using custom commands lets us publish [Prev | −seek | PlayPause | +seek | Next]
 * to both the notification and the system tile.
 *
 * Stability: changing any value here would break cached lockscreen notifications until
 * the app is fully restarted. Treat as part of the public API of [LibravaultMediaCallback].
 */
internal object CustomCommandActions {

    private const val PREFIX = "xyz.libravault.feature.player."

    /**
     * Tap target: play/pause toggle. No extras — the callback inspects the player's
     * current `playWhenReady` and calls `play()` or `pause()` accordingly.
     */
    const val PLAY_PAUSE = PREFIX + "PLAY_PAUSE"

    /**
     * Tap target: seek by a signed offset in milliseconds. Extras:
     *  - [EXTRA_OFFSET_MS]: signed [Long]; negative = seek back, positive = seek forward.
     *
     * The offset magnitude is sourced from the user's `defaultSkipDurationSec` preference
     * via [SkipDurationPreference.getSkipDurationMs] in [PlaybackService.onCreate] and
     * embedded in the button's [android.os.Bundle] at build time. Tapping a lockscreen
     * tile button routes through [LibravaultMediaCallback.dispatch], which reads the
     * offset and calls [androidx.media3.common.Player.seekTo] after [SeekClamp.clamp]
     * bounds-checks the target.
     */
    const val SEEK_BY = PREFIX + "SEEK_BY"

    /**
     * Tap target: jump to the previous item. No extras — dispatches straight to
     * [androidx.media3.common.Player.seekToPrevious], which restarts the current
     * (single) audiobook track when more than a few seconds in, or is a no-op
     * otherwise. There is no playlist to move to for a single-item audiobook.
     */
    const val PREVIOUS = PREFIX + "PREVIOUS"

    /**
     * Tap target: jump to the next item. No extras — dispatches straight to
     * [androidx.media3.common.Player.seekToNext], which is a no-op for a
     * single-item audiobook (no next track to move to). Published anyway so the
     * system tile carries the same five-button shape as the in-app mini-player.
     */
    const val NEXT = PREFIX + "NEXT"

    /** Bundle key for the signed seek offset in milliseconds (see [SEEK_BY]). */
    const val EXTRA_OFFSET_MS = "offsetMs"
}