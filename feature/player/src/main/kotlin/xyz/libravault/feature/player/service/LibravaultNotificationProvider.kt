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
 * Extends [DefaultMediaNotificationProvider] to control the compact notification /
 * lock-screen strip.
 *
 * Compact view preference: previous-chapter | play/pause | next-chapter.
 * Falls back to seek-back | play/pause | seek-forward for single-file audio
 * where chapter-skip commands are unavailable.
 *
 * Two problems with stock behaviour that we also fix:
 *  1. [DefaultMediaNotificationProvider] doesn't set [COMMAND_KEY_COMPACT_VIEW_INDEX] extras,
 *     so the compact-view slot selection is unpredictable (Samsung One UI shows 1–2 buttons).
 *  2. ExoPlayer marks [Player.COMMAND_SEEK_BACK] as disabled when the playback position is
 *     less than the seek increment from the start, causing [addNotificationActions] to skip
 *     the button entirely — so it gets no action index and can't be pinned in the compact strip.
 *
 * Fix: override [getMediaButtons] to force-enable seek-back and seek-forward regardless of
 * ExoPlayer's current position, then override [addNotificationActions] to scan the button
 * list and return the three action indices for the compact strip.
 */
internal class LibravaultNotificationProvider(context: Context) :
    DefaultMediaNotificationProvider(context) {

    /**
     * Force-enable seek-back and seek-forward so they are always added as notification
     * actions even when ExoPlayer says the command is unavailable (e.g. position = 0).
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
            val forceEnable = !button.isEnabled &&
                (button.playerCommand == Player.COMMAND_SEEK_BACK ||
                 button.playerCommand == Player.COMMAND_SEEK_FORWARD ||
                 button.playerCommand == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ||
                 button.playerCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            out.add(
                if (forceEnable)
                    CommandButton.Builder()
                        .setPlayerCommand(button.playerCommand)
                        .setIconResId(button.iconResId)
                        .setDisplayName(button.displayName)
                        .setEnabled(true)
                        .build()
                else
                    button
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
        // Let super add all actions to `builder` (we rely on its output).
        val superIndices = super.addNotificationActions(mediaSession, mediaButtons, builder, actionFactory)

        // Scan mediaButtons to find the notification-action index of each command.
        // Action index tracks only ENABLED buttons; super skips disabled ones.
        var prevChapterActionIdx  = -1
        var seekBackActionIdx     = -1
        var playPauseActionIdx    = -1
        var seekForwardActionIdx  = -1
        var nextChapterActionIdx  = -1
        var actionIdx = 0

        for (button in mediaButtons) {
            if (!button.isEnabled) continue
            when (button.playerCommand) {
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> if (prevChapterActionIdx < 0) prevChapterActionIdx = actionIdx
                Player.COMMAND_SEEK_BACK                   -> if (seekBackActionIdx    < 0) seekBackActionIdx    = actionIdx
                Player.COMMAND_PLAY_PAUSE                  -> if (playPauseActionIdx   < 0) playPauseActionIdx   = actionIdx
                Player.COMMAND_SEEK_FORWARD                -> if (seekForwardActionIdx < 0) seekForwardActionIdx = actionIdx
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM     -> if (nextChapterActionIdx < 0) nextChapterActionIdx = actionIdx
            }
            actionIdx++
        }

        // Compact view: prefer prev-chapter | play/pause | next-chapter.
        // Fall back to seek-back | play/pause | seek-forward for single-file audio
        // where chapter-skip commands are unavailable.
        val compact = buildList {
            if (prevChapterActionIdx >= 0 && playPauseActionIdx >= 0 && nextChapterActionIdx >= 0) {
                add(prevChapterActionIdx)
                add(playPauseActionIdx)
                add(nextChapterActionIdx)
            } else if (seekBackActionIdx >= 0 && playPauseActionIdx >= 0 && seekForwardActionIdx >= 0) {
                add(seekBackActionIdx)
                add(playPauseActionIdx)
                add(seekForwardActionIdx)
            } else if (playPauseActionIdx >= 0) {
                add(playPauseActionIdx)
            }
        }
        return if (compact.isNotEmpty()) compact.toIntArray() else superIndices
    }
}
