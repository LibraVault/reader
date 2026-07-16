package xyz.libravault.feature.player.service

/**
 * String constants for custom [androidx.media3.session.SessionCommand]s understood by
 * [PlaybackService.MediaButtonCallback], plus the [Bundle] extra keys they carry.
 *
 * Wired into [LibravaultNotificationProvider.Companion.buildStandardStrip] (which
 * publishes the lockscreen / Quick-Settings media tile strip) and into
 * [PlaybackService.MediaButtonCallback.onCustomCommand] which dispatches the
 * commands to the underlying player.
 *
 * Why custom commands (and not [androidx.media3.common.Player.COMMAND_SEEK_BACK] etc.):
 * the legacy `PlaybackStateCompat` that feeds the Android 13+ system media tile only
 * receives [androidx.media3.session.CommandButton]s whose `sessionCommand` has
 * `commandCode == COMMAND_CODE_CUSTOM`. Player-command buttons are filtered out in
 * `PlayerWrapper.createPlaybackStateCompat` and therefore never reach the platform
 * notification — only the in-app expanded notification renders them. Using custom
 * commands lets both surfaces share the same five buttons.
 *
 * Stability: changing any value here would break cached lockscreen notifications until
 * the app is fully restarted. Treat as part of the public API of [PlaybackService].
 */
internal object CustomCommandActions {

    private const val PREFIX = "xyz.libravault.feature.player."

    /** Tap target: previous media item (chapter/track). No extras. */
    const val PREVIOUS = PREFIX + "PREVIOUS"

    /** Tap target: next media item (chapter/track). No extras. */
    const val NEXT = PREFIX + "NEXT"

    /**
     * Tap target: play/pause toggle. No extras — the callback inspects the player's
     * current `playWhenReady` and calls `play()` or `pause()` accordingly.
     */
    const val PLAY_PAUSE = PREFIX + "PLAY_PAUSE"

    /**
     * Tap target: seek by a signed offset in milliseconds. Extras:
     *  - [EXTRA_OFFSET_MS]: signed [Long]; negative = seek back, positive = seek forward.
     *
     * The callback clamps the target to `[0, duration]` (see [SeekClamp.clamp]) — including
     * the `C.TIME_UNSET` duration case where ExoPlayer is still buffering.
     */
    const val SEEK_BY = PREFIX + "SEEK_BY"

    /** Bundle key for the signed seek offset in milliseconds (see [SEEK_BY]). */
    const val EXTRA_OFFSET_MS = "offsetMs"
}