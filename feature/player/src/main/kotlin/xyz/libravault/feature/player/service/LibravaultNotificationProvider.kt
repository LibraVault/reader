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
 * Extends [DefaultMediaNotificationProvider] to pin exactly three buttons in
 * the compact notification / lock-screen strip: seek-back | play/pause | seek-forward.
 *
 * Two problems with stock behaviour that we fix:
 *  1. [DefaultMediaNotificationProvider] doesn't set [COMMAND_KEY_COMPACT_VIEW_INDEX] extras
 *     reliably, so the compact-view slot selection is unpredictable.
 *  2. ExoPlayer marks [Player.COMMAND_SEEK_BACK] (and sometimes [Player.COMMAND_SEEK_FORWARD])
 *     as disabled when the playback position is near the start or end. The default provider
 *     skips disabled buttons, so they get no action index and can't appear in the strip.
 *
 * Fix strategy:
 *  - [getMediaButtons]: force-enable every disabled button so all buttons become
 *    notification actions.
 *  - [addNotificationActions]: locate the play/pause action by scanning for
 *    [Player.COMMAND_PLAY_PAUSE], then return [ppIdx-1, ppIdx, ppIdx+1] as the
 *    three compact-view slots (seek-back | play/pause | seek-forward).
 */
internal class LibravaultNotificationProvider(context: Context) :
    DefaultMediaNotificationProvider(context) {

    /**
     * Force-enable every button that is disabled so that all buttons are added as
     * notification actions regardless of current playback position.
     */
    override fun getMediaButtons(
        session: MediaSession,
        playerCommands: Player.Commands,
        customLayout: ImmutableList<CommandButton>,
        showPauseButton: Boolean,
    ): ImmutableList<CommandButton> {
        val base = super.getMediaButtons(session, playerCommands, customLayout, showPauseButton)
        val out = ImmutableList.Builder<CommandButton>()
        for (button in base) {
            out.add(
                if (button.isEnabled) button
                else CommandButton.Builder()
                    .setPlayerCommand(button.playerCommand)
                    .setIconResId(button.iconResId)
                    .setDisplayName(button.displayName)
                    .setEnabled(true)
                    .build()
            )
        }
        return out.build()
    }

    override fun addNotificationActions(
        mediaSession: MediaSession,
        mediaButtons: ImmutableList<CommandButton>,
        builder: NotificationCompat.Builder,
        actionFactory: MediaNotification.ActionFactory,
    ): IntArray {
        // Let super add all actions to `builder`.
        val superIndices = super.addNotificationActions(mediaSession, mediaButtons, builder, actionFactory)

        // Count the total enabled buttons (= total notification actions added by super).
        val totalActions = mediaButtons.count { it.isEnabled }

        // Find the play/pause action index by scanning enabled buttons in order.
        var playPauseIdx = -1
        var actionIdx = 0
        for (button in mediaButtons) {
            if (!button.isEnabled) continue
            if (button.playerCommand == Player.COMMAND_PLAY_PAUSE) {
                playPauseIdx = actionIdx
                break
            }
            actionIdx++
        }

        if (playPauseIdx < 0) return superIndices

        // Compact strip: the button to the left of play/pause and the one to the right.
        // Typically this resolves to: seek-back | play/pause | seek-forward.
        val compact = buildList {
            if (playPauseIdx > 0) add(playPauseIdx - 1)
            add(playPauseIdx)
            if (playPauseIdx + 1 < totalActions) add(playPauseIdx + 1)
        }
        return compact.toIntArray()
    }
}
