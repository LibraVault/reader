@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package xyz.libravault.feature.player.service

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import xyz.libravault.core.domain.model.ListeningProgress
import xyz.libravault.core.domain.usecase.GetAdjacentLibraryItemUseCase
import xyz.libravault.core.domain.usecase.GetListeningProgressUseCase
import xyz.libravault.core.domain.usecase.SaveListeningProgressUseCase

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
 *   custom action strings defined in [CustomCommandActions] (`PLAY_PAUSE`, `SEEK_BY`,
 *   `PREVIOUS`, `NEXT`). Without this base, Media3's `PlayerWrapper.createPlaybackStateCompat`
 *   filter (`sessionCommand != null AND sessionCommand.commandCode == COMMAND_CODE_CUSTOM AND
 *   isEnabled(button, availableSessionCommands, availablePlayerCommands)`) rejects all
 *   session-command buttons on the system tile.
 * - `availablePlayerCommands` is the full Player.Commands set with every command we publish
 *   ourselves as a custom button removed: [Player.COMMAND_SEEK_BACK],
 *   [Player.COMMAND_SEEK_FORWARD], [Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM],
 *   [Player.COMMAND_SEEK_TO_PREVIOUS], [Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM], and
 *   [Player.COMMAND_SEEK_TO_NEXT]. Leaving these in `availablePlayerCommands` would make
 *   `PlayerWrapper.createPlaybackStateCompat` *also* derive standard `ACTION_REWIND` /
 *   `ACTION_FAST_FORWARD` / `ACTION_SKIP_TO_PREVIOUS` / `ACTION_SKIP_TO_NEXT` bits from the
 *   standard-actions bitmask, duplicating the five custom buttons below on a tile that only
 *   has room for five slots. [Player.COMMAND_PLAY_PAUSE] is kept, since the system tile's
 *   central glyph is driven by `PlaybackStateCompat.state`, not by a separate action slot.
 * - `customLayout` is the five-button strip built by [buildStandardStrip]
 *   ([Prev | −seek | PlayPause | +seek | Next]). These buttons reach
 *   `PlaybackStateCompat.customActions` (via the filter above) on the system tile.
 *
 * # Prev/Next semantics
 *
 * [ChapterExtractor] currently always returns a single "Full Book" chapter — there is no
 * real per-file chapter navigation anywhere in the app yet. So Prev/Next on the lockscreen
 * tile switch to the previous/next sibling audio file within the same vault folder (ordered
 * by [xyz.libravault.core.domain.model.LibraryItem.filePath]) via [GetAdjacentLibraryItemUseCase]
 * — the practical "next chapter" for audiobooks split across multiple physical files (e.g.
 * "Chapter 01.mp3", "Chapter 02.mp3"). If there is no sibling file in that direction (already
 * at the first/last file in the folder), Prev/Next are a no-op. Switching files saves the
 * outgoing item's progress, resumes the incoming item from its last saved position, and
 * updates [PlaybackStateHolder] so the notification/lockscreen title, author, and artwork
 * follow the new file. This mirrors the in-app player's `PlayerViewModel.goToChapter` at the
 * player level, but operates on whole files since the service layer has no ViewModel-scoped
 * chapter list to draw on.
 *
 * [antenna]: https://github.com/AntennaPod/AntennaPod/blob/develop/playback/service/src/main/java/de/danoeh/antennapod/playback/service/internal/MediaLibrarySessionCallback.java
 */
internal class LibravaultMediaCallback(
    private val context: Context,
    private val player: ExoPlayer,
    private val seekStepMs: Long,
    private val playbackStateHolder: PlaybackStateHolder,
    private val getAdjacentItem: GetAdjacentLibraryItemUseCase,
    private val getListeningProgress: GetListeningProgressUseCase,
    private val saveListeningProgress: SaveListeningProgressUseCase,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : MediaSession.Callback {

    /**
     * Scope for [onCustomCommand] dispatch. Uses [Dispatchers.Main] (not `Unconfined` or
     * `Default`) so that after a suspending DB lookup (file-switch path), execution resumes
     * back on the main thread before touching [player] — ExoPlayer instances are confined to
     * the thread they were created on. Tests override [dispatcher] with `Unconfined` so
     * dispatch completes synchronously without a real Android main looper.
     */
    private val callbackScope = CoroutineScope(SupervisorJob() + dispatcher)

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
                .add(SessionCommand(CustomCommandActions.PLAY_PAUSE, Bundle.EMPTY))
                // SEEK_BY has the same action string for both ±seek directions; we only
                // need to advertise it once.
                .add(SessionCommand(CustomCommandActions.SEEK_BY, Bundle.EMPTY))
                .add(SessionCommand(CustomCommandActions.PREVIOUS, Bundle.EMPTY))
                .add(SessionCommand(CustomCommandActions.NEXT, Bundle.EMPTY))
                .build()

        // Populate the Player.Commands bitmask used by PlayerWrapper.createPlaybackStateCompat
        // to auto-derive the standard-actions bitmask (ACTION_PLAY_PAUSE, ACTION_REWIND,
        // ACTION_FAST_FORWARD, etc.). `addAllCommands()` is the Media3 1.3.1 equivalent of
        // AntennaPod's `addAllCommands()` on Media3 1.9. We then *remove* every command we
        // publish ourselves as a custom button in `customLayout` — leaving them in would make
        // the system tile derive standard actions for the same controls, duplicating our
        // five custom buttons on a tile that only has room for five slots.
        val playerCommands = Player.Commands.Builder()
            .addAllCommands()
            .remove(Player.COMMAND_SEEK_BACK)
            .remove(Player.COMMAND_SEEK_FORWARD)
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
            .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .remove(Player.COMMAND_SEEK_TO_NEXT)
            .build()

        val result = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(sessionCommands)
            .setAvailablePlayerCommands(playerCommands)
            .setCustomLayout(
                buildStandardStrip(
                    seekStepMs = seekStepMs,
                    displayNames = StripDisplayNames(
                        previous = context.getString(
                            androidx.media3.session.R.string.media3_controls_seek_to_previous_description,
                        ),
                        back = context.getString(
                            androidx.media3.session.R.string.media3_controls_seek_back_description,
                        ),
                        play = context.getString(
                            androidx.media3.session.R.string.media3_controls_play_description,
                        ),
                        forward = context.getString(
                            androidx.media3.session.R.string.media3_controls_seek_forward_description,
                        ),
                        next = context.getString(
                            androidx.media3.session.R.string.media3_controls_seek_to_next_description,
                        ),
                    ),
                ),
            )
            .build()
        Log.i(
            TAG,
            "onConnect: returning Accepted; sessionCommands.size=${sessionCommands.commands.size} " +
                "playerCommands.size=${playerCommands.size()} customLayout.size=5 seekStepMs=$seekStepMs",
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
        val future = SettableFuture.create<SessionResult>()
        callbackScope.launch {
            val result = try {
                dispatch(customCommand)
            } catch (t: Throwable) {
                Log.e(TAG, "onCustomCommand: dispatch threw", t)
                SessionResult(SessionResult.RESULT_ERROR_UNKNOWN)
            }
            future.set(result)
        }
        return future
    }

    /** Cancels [callbackScope]. Called from [PlaybackService.onDestroy]. */
    fun release() {
        callbackScope.cancel()
    }

    /**
     * Dispatches a custom session command to the underlying [ExoPlayer]. Tapping a
     * lockscreen / Quick-Settings ±seek button routes through here; the offset is read
     * from [CustomCommandActions.EXTRA_OFFSET_MS] in the [SessionCommand]'s extras bundle
     * (seeded by [buildStandardStrip] from the user's `defaultSkipDurationSec` preference)
     * and applied via [Player.seekTo] after [SeekClamp.clamp] bounds-checks the target.
     * Prev/Next switch to the previous/next sibling file — see the class KDoc.
     */
    private suspend fun dispatch(command: SessionCommand): SessionResult {
        return when (command.customAction) {
            CustomCommandActions.PLAY_PAUSE -> {
                if (player.playWhenReady) player.pause() else player.play()
                SessionResult(SessionResult.RESULT_SUCCESS)
            }
            CustomCommandActions.SEEK_BY -> {
                val deltaMs = command.customExtras?.getLong(CustomCommandActions.EXTRA_OFFSET_MS, 0L) ?: 0L
                val target = SeekClamp.clamp(
                    currentPosition = player.currentPosition,
                    deltaMs = deltaMs,
                    duration = player.duration,
                )
                player.seekTo(target)
                SessionResult(SessionResult.RESULT_SUCCESS)
            }
            CustomCommandActions.PREVIOUS -> switchToAdjacentItem(forward = false)
            CustomCommandActions.NEXT -> switchToAdjacentItem(forward = true)
            else -> {
                Log.w(TAG, "onCustomCommand: unknown action=${command.customAction}")
                SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
            }
        }
    }

    /**
     * Switches playback to the next/previous sibling file in the current item's vault
     * folder (see [GetAdjacentLibraryItemUseCase]). No-ops if no item is currently loaded
     * ([PlaybackStateHolder] is empty) or there is no sibling file in that direction (already
     * at the first/last file). Saves the outgoing item's progress, resumes the incoming item
     * from its last saved position (or the start), and mirrors the switch into
     * [PlaybackStateHolder] so the tile's title/author/artwork update immediately.
     */
    private suspend fun switchToAdjacentItem(forward: Boolean): SessionResult {
        val current = playbackStateHolder.state.value
        val itemId = current.itemId
        val vaultFolderId = current.vaultFolderId
        val filePath = current.filePath
        if (itemId == null || vaultFolderId == null || filePath == null) {
            Log.w(TAG, "switchToAdjacentItem: no item currently loaded, ignoring")
            return SessionResult(SessionResult.RESULT_SUCCESS)
        }

        val adjacentItem = if (forward) {
            getAdjacentItem.next(vaultFolderId, filePath)
        } else {
            getAdjacentItem.previous(vaultFolderId, filePath)
        }
        if (adjacentItem == null) {
            Log.i(
                TAG,
                "switchToAdjacentItem: already at the ${if (forward) "last" else "first"} " +
                    "file in vaultFolderId=$vaultFolderId",
            )
            return SessionResult(SessionResult.RESULT_SUCCESS)
        }

        val wasPlaying = player.isPlaying
        saveListeningProgress(
            ListeningProgress(
                itemId = itemId,
                positionMs = player.currentPosition,
                chapterIndex = 0,
                lastListenedAt = Instant.now(),
                playbackSpeed = player.playbackParameters.speed,
            ),
        )

        val resumeMs = getListeningProgress(adjacentItem.id)?.positionMs ?: 0L
        player.setMediaItem(MediaItem.fromUri(Uri.parse(adjacentItem.filePath)), resumeMs)
        player.prepare()
        if (wasPlaying) player.play()

        playbackStateHolder.update(
            itemId = adjacentItem.id,
            vaultFolderId = adjacentItem.vaultFolderId,
            filePath = adjacentItem.filePath,
            title = adjacentItem.title,
            author = adjacentItem.author,
            coverArtPath = adjacentItem.coverArtPath,
            isPlaying = wasPlaying,
        )
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    companion object {
        private const val TAG = "LibravaultMediaCallback"

        /**
         * Builds the standard five-button strip in positional order
         * `[Prev | −seek | PlayPause | +seek | Next]` for use with
         * [MediaSession.ConnectionResult.AcceptedResultBuilder.setCustomLayout] — the same
         * shape as the in-app player's [xyz.libravault.feature.player.components.PlaybackControls]
         * row.
         *
         * @param seekStepMs signed-offset magnitude embedded in each ±seek button's
         *   [Bundle] (positive for forward, negated for back). Sourced from
         *   [SkipDurationPreference.getSkipDurationMs] in [PlaybackService.onCreate] so
         *   the lockscreen strip honors the user's `defaultSkipDurationSec` setting at
         *   service-create time.
         * @param displayNames localized display strings for the five buttons, in
         *   positional order (previous / back / play / forward / next). Resolved at the
         *   call site (which has a real [Context]) so this helper stays Context-free and
         *   trivially unit-testable on the JVM.
         *
         * Icons are the bitmaps bundled in `androidx.media3.session` 1.3.1
         * (`media3_notification_*`). Display names come from the same module
         * (`media3_controls_*_description`). These are the same identifiers AntennaPod's
         * MediaLibrarySessionCallback uses.
         *
         * Each button is built with [SessionCommand] (with [CustomCommandActions] action
         * strings) so it survives the
         * `PlayerWrapper.createPlaybackStateCompat` filter — see the class KDoc for the
         * full rationale. Prev/Next are always enabled (matching the original notification
         * provider's behavior for these two slots) even though they're a no-op when there is
         * no sibling file in that direction — a stable five-button strip is preferable to one
         * whose shape changes based on playback state.
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
                    .setSessionCommand(SessionCommand(CustomCommandActions.PREVIOUS, Bundle()))
                    .setIconResId(androidx.media3.session.R.drawable.media3_notification_seek_to_previous)
                    .setDisplayName(displayNames.previous)
                    .setEnabled(true)
                    .build(),
            )
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
            builder.add(
                CommandButton.Builder()
                    .setSessionCommand(SessionCommand(CustomCommandActions.NEXT, Bundle()))
                    .setIconResId(androidx.media3.session.R.drawable.media3_notification_seek_to_next)
                    .setDisplayName(displayNames.next)
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
         * Localized display-name strings for the five buttons in
         * [buildStandardStrip], resolved at the call site.
         */
        data class StripDisplayNames(
            val previous: String,
            val back: String,
            val play: String,
            val forward: String,
            val next: String,
        )
    }
}
