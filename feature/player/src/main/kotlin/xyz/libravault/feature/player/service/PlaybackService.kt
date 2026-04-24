package xyz.libravault.feature.player.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import com.google.common.collect.ImmutableList
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground [MediaSessionService] that keeps audio playing when the app
 * is backgrounded, the screen is locked, or the user switches to another app.
 *
 * Lock screen / notification controls:
 *  ⏮  Seek back 30s  |  ⏯ Play/Pause  |  ⏭  Seek forward 30s
 *
 * Android Auto, headphone unplug, and audio focus are all handled by Media3.
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

        // ── Custom command buttons ────────────────────────────────────────────
        // Media3 uses CommandButton.Builder to define what appears in the
        // compact notification view (lock screen + status bar).
        // slots 0/1/2 = the three compact-view positions.
        val seekBackButton = CommandButton.Builder()
            .setDisplayName("Seek back 30s")
            .setSessionCommand(SessionCommand(SessionCommand.COMMAND_CODE_PLAYER_SEEK_BACK))
            .setIconResId(androidx.media3.session.R.drawable.media3_notification_seek_back)
            .build()

        val playPauseButton = CommandButton.Builder()
            .setDisplayName("Play / Pause")
            .setSessionCommand(SessionCommand(SessionCommand.COMMAND_CODE_PLAYER_PLAY_PAUSE))
            .setIconResId(androidx.media3.session.R.drawable.media3_notification_play)
            .build()

        val seekForwardButton = CommandButton.Builder()
            .setDisplayName("Seek forward 30s")
            .setSessionCommand(SessionCommand(SessionCommand.COMMAND_CODE_PLAYER_SEEK_FORWARD))
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
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
