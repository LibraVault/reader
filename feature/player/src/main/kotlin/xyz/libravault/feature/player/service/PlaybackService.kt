package xyz.libravault.feature.player.service

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground [MediaSessionService] — keeps audio playing when backgrounded or screen locked.
 *
 * Lock screen / notification compact view:
 *   ⏪ Seek back 30s  |  ⏯ Play/Pause  |  ⏩ Seek forward 30s
 *
 * In Media3 1.x the compact notification layout is controlled by setting a custom layout
 * of [CommandButton]s built from [Player.Command] values (NOT SessionCommand — those are
 * the deprecated androidx.media2 API).
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    @Inject
    lateinit var player: ExoPlayer

    override fun onCreate() {
        super.onCreate()

        val sessionActivity = packageManager
            .getLaunchIntentForPackage(packageName)
            ?.let { intent ->
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }

        // Build the three compact-view buttons using Player.Command constants
        val seekBackButton = CommandButton.Builder()
            .setDisplayName("Skip back 30s")
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setIconResId(androidx.media3.session.R.drawable.media3_notification_seek_back)
            .build()

        val playPauseButton = CommandButton.Builder()
            .setDisplayName("Play / Pause")
            .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
            .setIconResId(androidx.media3.session.R.drawable.media3_notification_play)
            .build()

        val seekForwardButton = CommandButton.Builder()
            .setDisplayName("Skip forward 30s")
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setIconResId(androidx.media3.session.R.drawable.media3_notification_seek_forward)
            .build()

        val builder = MediaSession.Builder(this, player)
            .setCustomLayout(ImmutableList.of(seekBackButton, playPauseButton, seekForwardButton))

        sessionActivity?.let { builder.setSessionActivity(it) }
        mediaSession = builder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            release()
            player.release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
