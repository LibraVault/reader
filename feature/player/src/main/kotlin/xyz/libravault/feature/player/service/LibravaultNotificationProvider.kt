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
 * `isEnabled=false` and gets dropped — explaining the "only play/previous" layout reported by
 * users). The previous build of this provider pinned exactly three compact-view slots and
 * had an index-resolution bug (disabled buttons counted into the action index) that caused
 * slot selection to land on the wrong command, which manifested as taps on the play/pause
 * glyph appearing to do nothing. This implementation pins all five buttons in a stable order
 * and returns the indices `[0..size)` so the platform tile receives the full strip.
 *
 * Any buttons the session publishes via `MediaSession.setCustomLayout(...)` are appended
 * after the standard 5 — they show up in the expanded notification (and in the compact view
 * when the total is 5 or fewer). Earlier versions of this provider silently dropped them.
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
        @Suppress("UNUSED_PARAMETER") playerCommands: Player.Commands,
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
            // Force-enable so the strip layout is stable across playlist shapes
            // (single-track audiobooks report this command as unavailable). Taps on
            // an unavailable prev/next route through the standard seek/transport path
            // and are no-op'd by ExoPlayer; the system still renders the button.
            .setEnabled(true)
            .build()

        val skipBackButton = CommandButton.Builder()
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setIconResId(Icons.rewind)
            .setDisplayName(
                context.getString(
                    androidx.media3.session.R.string.media3_controls_seek_back_description
                )
            )
            .setEnabled(true)
            .build()

        val skipForwardButton = CommandButton.Builder()
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setIconResId(Icons.ffwd)
            .setDisplayName(
                context.getString(
                    androidx.media3.session.R.string.media3_controls_seek_forward_description
                )
            )
            .setEnabled(true)
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
            // The icon flips between play and pause via `showPauseButton`, which is
            // decoupled from the underlying `COMMAND_PLAY_PAUSE` availability — a paused
            // player still receives `COMMAND_PLAY_PAUSE` and the button is enabled,
            // it just renders the play glyph. Tap target is identical either way.
            .setEnabled(true)
            .build()

        val nextButton = CommandButton.Builder()
            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .setIconResId(Icons.next)
            .setDisplayName(
                context.getString(
                    androidx.media3.session.R.string.media3_controls_seek_to_next_description
                )
            )
            .setEnabled(true)
            .build()

        // Positional order: Prev | −seek | PlayPause | +seek | Next | (custom buttons).
        // Custom buttons are appended so the pinned standard-5 strip is preserved
        // (lockscreen tile always shows the same Prev/−seek/PlayPause/+seek/Next),
        // but anything the session publishes via `MediaSession.setCustomLayout(...)`
        // (e.g. bookmark, sleep timer, download) is honored instead of silently
        // dropped.
        //
        // Mirrors `DefaultMediaNotificationProvider`'s own filter: only enabled
        // buttons with a non-null `sessionCommand` survive into the action list —
        // player-command buttons are not part of the custom layout contract.
        val standardFive = ImmutableList.of(
            prevButton, skipBackButton, playPauseButton, skipForwardButton, nextButton,
        )
        if (customLayout.isEmpty()) return standardFive

        val customButtons = customLayout.filter {
            it.sessionCommand != null && it.isEnabled
        }
        return if (customButtons.isEmpty()) {
            standardFive
        } else {
            ImmutableList.builder<CommandButton>()
                .addAll(standardFive)
                .addAll(customButtons)
                .build()
        }
    }

    /**
     * Adds every button from [mediaButtons] to the notification (delegated to super),
     * then returns one compact-view index per action so the system tile renders the
     * whole strip. The standard 5 occupy positions `[0..4]`; any custom buttons
     * appended by [getMediaButtons] occupy positions `[5..N)` and fall through to the
     * expanded notification (the platform caps visible compact slots at 5).
     *
     * Per `DefaultMediaNotificationProvider` source, `super.addNotificationActions(...)`
     * calls `builder.addAction(...)` for every entry in [mediaButtons] regardless of
     * `CommandButton.isEnabled`. Disabled buttons still occupy an action-list slot —
     * they just render dimmed. So every index we hand back is in-bounds.
     *
     * The pure helper that produces the index array lives in [Companion.compactViewIndicesFor]
     * and is unit-tested separately to make the invariant explicit.
     */
    override fun addNotificationActions(
        mediaSession: MediaSession,
        mediaButtons: ImmutableList<CommandButton>,
        builder: NotificationCompat.Builder,
        actionFactory: MediaNotification.ActionFactory,
    ): IntArray {
        super.addNotificationActions(mediaSession, mediaButtons, builder, actionFactory)
        return compactViewIndicesFor(mediaButtons.size)
    }

    companion object {
        /**
         * Pure helper that returns the compact-view indices for the strip.
         *
         * Always returns every index `[0, size)` so the system tile renders
         * the entire action list — including any custom buttons appended after
         * the standard 5 by [getMediaButtons]. The platform caps the visible
         * compact slots at 5, so when the list grows beyond 5 the system still
         * shows just the first 5 (the pinned Prev / −seek / PlayPause / +seek /
         * Next) and the rest fall through to the expanded notification only.
         * Returning all indices (rather than `[0..4]` hardcoded) keeps the
         * helper in sync with the actual action-list size so callers that
         * supply fewer than 5 buttons still get in-bounds indices handed back
         * to `Notification.MediaStyle.setShowActionsInCompactView`.
         */
        internal fun compactViewIndicesFor(actionListSize: Int): IntArray =
            IntArray(actionListSize.coerceAtLeast(0)) { it }
    }
}
