@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package xyz.libravault.feature.player.service

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Top-level [MediaSession.Callback] that drives the lockscreen / Quick-Settings media tile
 * for audiobooks.
 *
 * # Architecture
 *
 * This implementation follows the pattern used by [AntennaPod's
 * MediaLibrarySessionCallback][antenna] (Media3 1.9, github.com/AntennaPod/AntennaPod) —
 * with one important difference: AntennaPod uses Media3 1.9 APIs (`setMediaButtonPreferences`,
 * `CommandButton.Builder(int icon)`) that don't exist in our pinned Media3 1.3.1, so the
 * primitives used here are the 1.3.1 equivalents.
 *
 * # What this publishes to the system media tile
 *
 * For each connecting controller, `onConnect` returns a `ConnectionResult` whose:
 * - `availableSessionCommands` starts from
 *   [MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS] and adds the two
 *   custom action strings defined in [CustomCommandActions] (`PLAY_PAUSE` and `SEEK_BY`).
 *   Without this base, Media3's `PlayerWrapper.createPlaybackStateCompat` filter
 *   (`sessionCommand != null AND sessionCommand.commandCode == COMMAND_CODE_CUSTOM AND
 *   isEnabled(button, availableSessionCommands, availablePlayerCommands)`) rejects all
 *   session-command buttons on the system tile.
 * - `availablePlayerCommands` is the full Player.Commands set with
 *   [Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM] and [Player.COMMAND_SEEK_TO_PREVIOUS]
 *   **removed** (single-item audiobook playlists have no prev/next concept — AntennaPod does
 *   the same with their podcast episodes). The standard-actions bitmask the system tile
 *   reads from is auto-derived from these commands, so the user-visible `[<<, ▶, >>]` are
 *   produced by `PlayerWrapper.createPlaybackStateCompat`'s `convertCommandToPlaybackStateActions`
 *   for [Player.COMMAND_SEEK_BACK] / [Player.COMMAND_PLAY_PAUSE] /
 *   [Player.COMMAND_SEEK_FORWARD].
 * - `customLayout` is the three-button strip built by
 *   [buildStandardStrip] ([−seek | PlayPause | +seek]). These buttons reach
 *   `PlaybackStateCompat.customActions` (via the filter above) on the system tile.
 *
 * [antenna]: https://github.com/AntennaPod/AntennaPod/blob/develop/playback/service/src/main/java/de/danoeh/antennapod/playback/service/internal/MediaLibrarySessionCallback.java
 */
internal class LibravaultMediaCallback(
    private val context: Context,
    private val player: ExoPlayer,
    private val seekStepMs: Long,
) : MediaSession.Callback {

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        Log.i(
            TAG,
            "onConnect: pkg=${controller.packageName} " +
                "interfaceVersion=${controller.interfaceVersion}",
        )
        // `DEFAULT_SESSION_AND_LIBRARY_COMMANDS` provides a baseline set of session commands
        // that the system already trusts (including COMMAND_PLAY_PAUSE and similar).
        // Building `availableSessionCommands` from scratch with only our 2 custom actions
        // would leave the standard playback actions unavailable to the controller, which
        // causes PlaybackStateCompat to omit standard transport actions like SEEK_BACK /
        // SEEK_FORWARD — leaving the user with the broken 2-button layout the system has
        // been showing.
        val sessionCommands =
            MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(CustomCommandActions.PLAY_PAUSE, Bundle.EMPTY))
                // SEEK_BY has the same action string for both ±seek directions; we only
                // need to advertise it once.
                .add(SessionCommand(CustomCommandActions.SEEK_BY, Bundle.EMPTY))
                .build()

        // Populate the Player.Commands bitmask used by PlayerWrapper.createPlaybackStateCompat
        // to auto-derive the standard-actions bitmask (ACTION_PLAY_PAUSE, ACTION_REWIND,
        // ACTION_FAST_FORWARD, etc.). `addAllCommands()` is the Media3 1.3.1 equivalent of
        // AntennaPod's `addAllCommands()` on Media3 1.9. We then *remove* Prev/Next because
        // single-track audiobooks don't have prev/next media items and we don't want the
        // system tile to show a phantom button.
        val playerCommands = Player.Commands.Builder()
            .addAllCommands()
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
            .build()

        val result = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(sessionCommands)
            .setAvailablePlayerCommands(playerCommands)
            .setCustomLayout(
                buildStandardStrip(
                    seekStepMs = seekStepMs,
                    displayNames = StripDisplayNames(
                        back = context.getString(
                            androidx.media3.session.R.string.media3_controls_seek_back_description,
                        ),
                        play = context.getString(
                            androidx.media3.session.R.string.media3_controls_play_description,
                        ),
                        forward = context.getString(
                            androidx.media3.session.R.string.media3_controls_seek_forward_description,
                        ),
                    ),
                ),
            )
            .build()
        Log.i(
            TAG,
            "onConnect: returning Accepted; sessionCommands.size=${sessionCommands.commands.size} " +
                "playerCommands.size=${playerCommands.size()} customLayout.size=3 seekStepMs=$seekStepMs",
        )
        return result
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        Log.i(
            TAG,
            "onCustomCommand: pkg=${controller.packageName} action=${customCommand.customAction} args=$args",
        )
        return try {
            Futures.immediateFuture(dispatch(customCommand))
        } catch (t: Throwable) {
            Log.e(TAG, "onCustomCommand: dispatch threw", t)
            Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_UNKNOWN))
        }
    }

    /**
     * Dispatches a custom session command to the underlying [ExoPlayer]. Tapping a
     * lockscreen / Quick-Settings ±seek button routes through here; the offset is read
     * from [CustomCommandActions.EXTRA_OFFSET_MS] in the [SessionCommand]'s extras bundle
     * (seeded by [buildStandardStrip] from the user's `defaultSkipDurationSec` preference)
     * and applied via [Player.seekTo] after [SeekClamp.clamp] bounds-checks the target.
     */
    private fun dispatch(command: SessionCommand): SessionResult {
        when (command.customAction) {
            CustomCommandActions.PLAY_PAUSE -> {
                if (player.playWhenReady) player.pause() else player.play()
            }
            CustomCommandActions.SEEK_BY -> {
                val deltaMs = command.customExtras?.getLong(CustomCommandActions.EXTRA_OFFSET_MS, 0L) ?: 0L
                val target = SeekClamp.clamp(
                    currentPosition = player.currentPosition,
                    deltaMs = deltaMs,
                    duration = player.duration,
                )
                player.seekTo(target)
            }
            else -> {
                Log.w(TAG, "onCustomCommand: unknown action=${command.customAction}")
                return SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
            }
        }
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    companion object {
        private const val TAG = "LibravaultMediaCallback"

        /**
         * Builds the standard three-button strip in positional order
         * `[−seek | PlayPause | +seek]` for use with
         * [MediaSession.ConnectionResult.AcceptedResultBuilder.setCustomLayout].
         *
         * @param seekStepMs signed-offset magnitude embedded in each ±seek button's
         *   [Bundle] (positive for forward, negated for back). Sourced from
         *   [SkipDurationPreference.getSkipDurationMs] in [PlaybackService.onCreate] so
         *   the lockscreen strip honors the user's `defaultSkipDurationSec` setting at
         *   service-create time.
         * @param displayNames localized display strings for the three buttons, in
         *   positional order (back / play / forward). Resolved at the call site (which
         *   has a real [Context]) so this helper stays Context-free and trivially
         *   unit-testable on the JVM.
         *
         * Icons are the bitmaps bundled in `androidx.media3.session` 1.3.1
         * (`media3_notification_*`). Display names come from the same module
         * (`media3_controls_*_description`). These are the same identifiers AntennaPod's
         * MediaLibrarySessionCallback uses.
         *
         * Each button is built with [SessionCommand] (with [CustomCommandActions] action
         * strings) so it survives the
         * `PlayerWrapper.createPlaybackStateCompat` filter — see the class KDoc for the
         * full rationale.
         */
        @JvmStatic
        fun buildStandardStrip(
            seekStepMs: Long,
            displayNames: StripDisplayNames,
        ): ImmutableList<CommandButton> {
            require(seekStepMs > 0L) { "seekStepMs must be positive (got $seekStepMs)" }
            val builder = ImmutableList.builder<CommandButton>()

            builder.add(
                CommandButton.Builder()
                    .setSessionCommand(seekByCommand(-seekOffset(seekStepMs)))
                    .setIconResId(androidx.media3.session.R.drawable.media3_notification_seek_back)
                    .setDisplayName(displayNames.back)
                    .setEnabled(true)
                    .build(),
            )
            builder.add(
                CommandButton.Builder()
                    .setSessionCommand(SessionCommand(CustomCommandActions.PLAY_PAUSE, Bundle()))
                    .setIconResId(androidx.media3.session.R.drawable.media3_notification_play)
                    .setDisplayName(displayNames.play)
                    .setEnabled(true)
                    .build(),
            )
            builder.add(
                CommandButton.Builder()
                    .setSessionCommand(seekByCommand(seekOffset(seekStepMs)))
                    .setIconResId(androidx.media3.session.R.drawable.media3_notification_seek_forward)
                    .setDisplayName(displayNames.forward)
                    .setEnabled(true)
                    .build(),
            )

            return builder.build()
        }

        /**
         * Pure helper — returns the signed seek offset for the given positive [seekStepMs]
         * and direction. Forward direction returns the magnitude; backward returns its
         * negation. Extracted from [buildStandardStrip] so it can be unit-tested on the
         * JVM without needing a real [android.os.Bundle] (which is not mocked in plain
         * JUnit 5 tests).
         *
         * @throws IllegalArgumentException if [seekStepMs] is not strictly positive.
         */
        @JvmStatic
        internal fun seekOffset(seekStepMs: Long): Long {
            require(seekStepMs > 0L) { "seekStepMs must be positive (got $seekStepMs)" }
            return seekStepMs
        }

        /**
         * Pure helper — returns the negation of [seekOffset] for the back direction.
         * Pairs with [seekOffset] for the forward direction so each ±seek button carries
         * a deterministic, testable signed offset.
         */
        @JvmStatic
        internal fun seekOffsetBack(seekStepMs: Long): Long {
            require(seekStepMs > 0L) { "seekStepMs must be positive (got $seekStepMs)" }
            return -seekStepMs
        }

        /**
         * Pure helper — wraps [offsetMs] in the [Bundle] that
         * [androidx.media3.session.SessionCommand] expects. Lives next to [buildStandardStrip]
         * so the offset-encoding logic stays in one place; tests cover [seekOffset] /
         * [seekOffsetBack] and the strip shape separately.
         */
        @JvmStatic
        internal fun seekByCommand(offsetMs: Long): SessionCommand =
            SessionCommand(
                CustomCommandActions.SEEK_BY,
                Bundle().apply { putLong(CustomCommandActions.EXTRA_OFFSET_MS, offsetMs) },
            )

        /**
         * Localized display-name strings for the three buttons in
         * [buildStandardStrip], resolved at the call site.
         */
        data class StripDisplayNames(
            val back: String,
            val play: String,
            val forward: String,
        )
    }
}