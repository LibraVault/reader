@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package xyz.libravault.feature.player.service

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.core.app.NotificationCompat
import com.google.common.collect.ImmutableList

/**
 * Custom [DefaultMediaNotificationProvider] for the lockscreen / Quick-Settings media tile
 * compact strip.
 *
 * ## What this renders
 *
 * On the Android 13+ system media tile (lockscreen "Live notifications" and Quick-Settings
 * media output), this provider publishes a five-button strip:
 *
 * ```
 *   [ Prev ]   [ − N s ]   [ Play / Pause ]   [ + N s ]   [ Next ]
 * ```
 *
 * Where `N` is the user's `defaultSkipDurationSec` preference (5–120 seconds, default 30 s,
 * configured in Settings → Playback → Skip duration).
 *
 * ## Why a custom provider
 *
 * The default [DefaultMediaNotificationProvider] builds a maximum of three actions (and on
 * a single-track audiobook playlist the right-hand seek action frequently ends up
 * `isEnabled=false` and gets dropped — explaining the "only play/previous" layout reported
 * by users). The previous build of this provider pinned exactly three compact-view slots and
 * had an index-resolution bug (disabled buttons counted into the action index) that caused
 * slot selection to land on the wrong command, which manifested as taps on the play/pause
 * glyph appearing to do nothing. This implementation pins all five buttons in a stable order
 * and returns the indices `[0, 1, 2, 3, 4]` so the platform tile receives the full strip.
 *
 * ## Runtime setting changes
 *
 * The seek increment read by `Player.seekBack`/`seekForward` is fixed at ExoPlayer build
 * time in [PlayerModule] and can't be mutated afterward. A change to
 * `defaultSkipDurationSec` in Settings therefore takes effect on the lockscreen strip on
 * the next app start, while reader and library mini-player ±seek buttons honor the
 * preference live (see
 * [xyz.libravault.feature.library.LibraryViewModel.seekBy] and the reader equivalent).
 *
 * ## Icon set (Media3 1.3.1 API surface)
 *
 * [CommandButton.Builder] in this Media3 release accepts a resource id via
 * [CommandButton.Builder.setIconResId] rather than an icon-constant int. We resolve the
 * icons from `androidx.media3.ui` which ships equivalent bitmaps for the standard
 * transport actions.
 */
internal class LibravaultNotificationProvider(context: Context) :
    DefaultMediaNotificationProvider(context) {

    private val context: Context = context.applicationContext

    /** Bundled transport icons shipped by `androidx.media3.ui` as `exo_notification_*` resources. */
    private object Icons {
        val play     = androidx.media3.ui.R.drawable.exo_notification_play
        val pause    = androidx.media3.ui.R.drawable.exo_notification_pause
        val previous = androidx.media3.ui.R.drawable.exo_notification_previous
        val next     = androidx.media3.ui.R.drawable.exo_notification_next
        val rewind   = androidx.media3.ui.R.drawable.exo_notification_rewind
        val ffwd     = androidx.media3.ui.R.drawable.exo_notification_fastforward
    }

    override fun getMediaButtons(
        session: MediaSession,
        playerCommands: Player.Commands,
        customLayout: ImmutableList<CommandButton>,
        showPauseButton: Boolean,
    ): ImmutableList<CommandButton> {
        val prevButton = CommandButton.Builder()
            .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .setIconResId(Icons.previous)
            .setDisplayName(
                context.getString(
                    androidx.media3.session.R.string.media3_controls_seek_to_previous_description
                )
            )
            .setEnabled(playerCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
            .build()

        val skipBackButton = CommandButton.Builder()
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setIconResId(Icons.rewind)
            .setDisplayName(
                context.getString(
                    androidx.media3.session.R.string.media3_controls_seek_back_description
                )
            )
            .setEnabled(playerCommands.contains(Player.COMMAND_SEEK_BACK))
            .build()

        val skipForwardButton = CommandButton.Builder()
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setIconResId(Icons.ffwd)
            .setDisplayName(
                context.getString(
                    androidx.media3.session.R.string.media3_controls_seek_forward_description
                )
            )
            .setEnabled(playerCommands.contains(Player.COMMAND_SEEK_FORWARD))
            .build()

        val playPauseButton = CommandButton.Builder()
            .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
            .setIconResId(if (showPauseButton) Icons.pause else Icons.play)
            .setDisplayName(
                context.getString(
                    if (showPauseButton)
                        androidx.media3.session.R.string.media3_controls_pause_description
                    else
                        androidx.media3.session.R.string.media3_controls_play_description
                )
            )
            .setEnabled(playerCommands.contains(Player.COMMAND_PLAY_PAUSE))
            .build()

        val nextButton = CommandButton.Builder()
            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .setIconResId(Icons.next)
            .setDisplayName(
                context.getString(
                    androidx.media3.session.R.string.media3_controls_seek_to_next_description
                )
            )
            .setEnabled(playerCommands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
            .build()

        // Positional order: Prev | −seek | PlayPause | +seek | Next
        return ImmutableList.of(prevButton, skipBackButton, playPauseButton, skipForwardButton, nextButton)
    }

    /**
     * Adds the five buttons to the notification and returns all five as compact-view
     * indices. The superclass invocation still appends every button as a notification
     * action, so the expanded notification also ends up with five actions in the same
     * order. Android 13+ honors up to five compact-view indices; older versions display
     * the first three.
     */
    override fun addNotificationActions(
        mediaSession: MediaSession,
        mediaButtons: ImmutableList<CommandButton>,
        builder: NotificationCompat.Builder,
        actionFactory: MediaNotification.ActionFactory,
    ): IntArray {
        super.addNotificationActions(mediaSession, mediaButtons, builder, actionFactory)
        return intArrayOf(0, 1, 2, 3, 4)
    }
}
