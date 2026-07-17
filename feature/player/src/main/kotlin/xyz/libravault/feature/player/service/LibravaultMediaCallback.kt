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
 *   [MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS] and adds the four
 *   custom action strings defined in [CustomCommandActions]. Without this base, Media3's
 *   `PlayerWrapper.createPlaybackStateCompat` filter (`sessionCommand != null AND
 *   sessionCommand.commandCode == COMMAND_CODE_CUSTOM AND isEnabled(button,
 *   availableSessionCommands, availablePlayerCommands)`) rejects all session-command
 *   buttons on the system tile.
 * - `availablePlayerCommands` is the full Player.Commands set with
 *   [Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM] and [Player.COMMAND_SEEK_TO_PREVIOUS]
 *   **removed** (single-item audiobook playlists have no prev/next concept — AntennaPod does
 *   the same with their podcast episodes). The standard-actions bitmask the system tile
 *   reads from is auto-derived from these commands, so the user-visible `[<<, ▶, >>]` are
 *   produced by `PlayerWrapper.createPlaybackStateCompat`'s `convertCommandToPlaybackStateActions`
 *   for [Player.COMMAND_SEEK_BACK] / [Player.COMMAND_PLAY_PAUSE] /
 *   [Player.COMMAND_SEEK_FORWARD].
 * - `customLayout` is the five-button strip built by
 *   [buildStandardStrip]. These buttons reach `PlaybackStateCompat.customActions` (via
 *   the filter above) on the system tile, giving us prev / −seek / play-pause / +seek /
 *   next.
 *
 * [antenna]: https://github.com/AntennaPod/AntennaPod/blob/develop/playback/service/src/main/java/de/danoeh/antennapod/playback/service/internal/MediaLibrarySessionCallback.java
 */
internal class LibravaultMediaCallback(
    private val context: Context,
    private val player: ExoPlayer,
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
        // Building `availableSessionCommands` from scratch with only our 4 custom actions
        // would leave the standard playback actions unavailable to the controller, which
        // causes PlaybackStateCompat to omit standard transport actions like SEEK_BACK /
        // SEEK_FORWARD — leaving the user with the broken 2-button layout the system has
        // been showing.
        val sessionCommands =
            MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(CustomCommandActions.PREVIOUS, Bundle.EMPTY))
                .add(SessionCommand(CustomCommandActions.PLAY_PAUSE, Bundle.EMPTY))
                .add(SessionCommand(CustomCommandActions.NEXT, Bundle.EMPTY))
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
            .setCustomLayout(buildStandardStrip(context))
            .build()
        Log.i(
            TAG,
            "onConnect: returning Accepted; sessionCommands.size=${sessionCommands.commands.size} " +
                "playerCommands.size=${playerCommands.size()} customLayout.size=5",
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
     * lockscreen / Quick-Settings button routes through here. ±seek uses
     * [Player.seekBack] / [Player.seekForward] which respect the `seekBackIncrementMs` /
     * `seekForwardIncrementMs` configured on the ExoPlayer in [PlayerModule].
     */
    private fun dispatch(command: SessionCommand): SessionResult {
        when (command.customAction) {
            CustomCommandActions.PREVIOUS -> {
                if (player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) {
                    player.seekToPreviousMediaItem()
                }
            }
            CustomCommandActions.NEXT -> {
                if (player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) {
                    player.seekToNextMediaItem()
                }
            }
            CustomCommandActions.PLAY_PAUSE -> {
                if (player.playWhenReady) player.pause() else player.play()
            }
            CustomCommandActions.SEEK_BY -> {
                // We don't actually use SEEK_BY for system-tile taps; the ±seek buttons on
                // the tile are wired through COMMAND_SEEK_BACK / COMMAND_SEEK_FORWARD which
                // route to onPlayerCommandRequest, not through this custom-command dispatcher.
                // Keeping the dispatch entry so future custom-session-command buttons (e.g. a
                // big "skip 30s" chip in some hypothetical future layout) can dispatch via
                // [CustomCommandActions.SEEK_BY] with a signed offset bundle.
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
        private const val TAG = "LibravaultPlayback"

        /**
         * Builds the standard five-button strip in positional order
         * `[Prev | −seek | PlayPause | +seek | Next]` for use with
         * [MediaSession.ConnectionResult.AcceptedResultBuilder.setCustomLayout].
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
        fun buildStandardStrip(context: Context): ImmutableList<CommandButton> {
            val builder = ImmutableList.builder<CommandButton>()

            builder.add(
                CommandButton.Builder()
                    .setSessionCommand(SessionCommand(CustomCommandActions.PREVIOUS, Bundle.EMPTY))
                    .setIconResId(androidx.media3.session.R.drawable.media3_notification_seek_to_previous)
                    .setDisplayName(
                        context.getString(
                            androidx.media3.session.R.string.media3_controls_seek_to_previous_description,
                        ),
                    )
                    .setEnabled(true)
                    .build(),
            )
            builder.add(
                CommandButton.Builder()
                    .setSessionCommand(
                        SessionCommand(
                            CustomCommandActions.SEEK_BY,
                            Bundle().apply { putLong(CustomCommandActions.EXTRA_OFFSET_MS, -DEFAULT_SEEK_STEP_MS) },
                        ),
                    )
                    .setIconResId(androidx.media3.session.R.drawable.media3_notification_seek_back)
                    .setDisplayName(
                        context.getString(
                            androidx.media3.session.R.string.media3_controls_seek_back_description,
                        ),
                    )
                    .setEnabled(true)
                    .build(),
            )
            builder.add(
                CommandButton.Builder()
                    .setSessionCommand(SessionCommand(CustomCommandActions.PLAY_PAUSE, Bundle.EMPTY))
                    .setIconResId(androidx.media3.session.R.drawable.media3_notification_play)
                    .setDisplayName(
                        context.getString(
                            androidx.media3.session.R.string.media3_controls_play_description,
                        ),
                    )
                    .setEnabled(true)
                    .build(),
            )
            builder.add(
                CommandButton.Builder()
                    .setSessionCommand(
                        SessionCommand(
                            CustomCommandActions.SEEK_BY,
                            Bundle().apply { putLong(CustomCommandActions.EXTRA_OFFSET_MS, DEFAULT_SEEK_STEP_MS) },
                        ),
                    )
                    .setIconResId(androidx.media3.session.R.drawable.media3_notification_seek_forward)
                    .setDisplayName(
                        context.getString(
                            androidx.media3.session.R.string.media3_controls_seek_forward_description,
                        ),
                    )
                    .setEnabled(true)
                    .build(),
            )
            builder.add(
                CommandButton.Builder()
                    .setSessionCommand(SessionCommand(CustomCommandActions.NEXT, Bundle.EMPTY))
                    .setIconResId(androidx.media3.session.R.drawable.media3_notification_seek_to_next)
                    .setDisplayName(
                        context.getString(
                            androidx.media3.session.R.string.media3_controls_seek_to_next_description,
                        ),
                    )
                    .setEnabled(true)
                    .build(),
            )

            return builder.build()
        }

        /**
         * Default seek step (ms) embedded in the ±seek buttons' [Bundle]. Overridden at
         * build time by [PlayerModule] which reads the user's `defaultSkipDurationSec`
         * preference. This default is only used if a button's [Bundle] extras are somehow
         * missing — the runtime path actually dispatches through
         * [MediaSession.setCustomLayout] + [Player.seekBack]/[Player.seekForward] which
         * use the live `seekBackIncrementMs` on the player.
         */
        private const val DEFAULT_SEEK_STEP_MS = 30_000L
    }
}