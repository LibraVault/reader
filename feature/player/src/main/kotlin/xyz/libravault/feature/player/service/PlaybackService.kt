package xyz.libravault.feature.player.service

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import xyz.libravault.app.MainActivity
import javax.inject.Inject

/**
 * Foreground [MediaSessionService] that keeps audio playing when the app
 * is backgrounded, the screen is locked, or the user switches to another app.
 *
 * Handles:
 *  - Audio focus (pauses on call, resumes after)
 *  - Lock screen / notification media controls
 *  - Android Auto (MediaSession exposes the queue automatically)
 *  - Headphone unplug (pauses on AudioBecomingNoisy)
 *
 * Declared in AndroidManifest.xml — see manifest for the intent filter.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    @Inject
    lateinit var player: ExoPlayer

    override fun onCreate() {
        super.onCreate()

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
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
