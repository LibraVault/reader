@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package xyz.libravault.feature.player.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import xyz.libravault.feature.player.R

/**
 * Foreground [MediaSessionService] — keeps audio playing when backgrounded or screen locked.
 *
 * # Notification + lockscreen tile plumbing
 *
 * - **Notification provider**: stock [DefaultMediaNotificationProvider] with our small-icon
 *   override. We deliberately don't subclass it — the standard provider renders a
 *   notification with up to 3 transport actions derived from `Player.Commands`, and our
 *   ±seek buttons are surfaced through the same standard-actions path (not the customLayout
 *   path) on older Android versions where the notification IS the lockscreen surface.
 *
 * - **Lockscreen / Quick-Settings tile (Android 13+)**: populated by [LibravaultMediaCallback]
 *   via [MediaSession.Callback.onConnect]. See that class for the full rationale. The
 *   callback publishes a 5-button strip
 *   `[Prev | −seek | PlayPause | +seek | Next]` to `MediaSession.customLayout` and populates
 *   `availablePlayerCommands` with all standard commands (minus prev/next media items), so
 *   the system tile shows the standard-actions bitmask `[<<, ▶, >>]` plus the 5 custom
 *   actions as `PlaybackStateCompat.customActions`.
 *
 * # Why we use MediaSessionService (not MediaLibraryService)
 *
 * AntennaPod uses MediaLibraryService because they need a browsable library for Android Auto.
 * Libravault has no Android Auto integration, so [MediaSessionService] is enough. This also
 * keeps the service surface minimal — no `onGetLibraryRoot` / `onGetChildren` overhead.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    @Inject
    lateinit var player: ExoPlayer

    @SuppressLint("UnsafeOptInUsageError")
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate: start")

        try {
            val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(PLAYBACK_CHANNEL_ID)
                .setChannelName(R.string.playback_channel_name)
                .build()
            setMediaNotificationProvider(notificationProvider)
            Log.i(TAG, "onCreate: notification provider set (DefaultMediaNotificationProvider)")
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate: setMediaNotificationProvider threw", t)
            // Non-fatal — playback works without the notification provider.
        }

        val sessionActivity = packageManager
            .getLaunchIntentForPackage(packageName)
            ?.let { intent ->
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }

        try {
            val builder = MediaSession.Builder(this, player)
                .setCallback(LibravaultMediaCallback(this, player))
            sessionActivity?.let { builder.setSessionActivity(it) }
            mediaSession = builder.build()
            Log.i(TAG, "onCreate: MediaSession built; id=${mediaSession?.id}")
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate: MediaSession.Builder.build() threw", t)
            throw t
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        val session = mediaSession
        Log.i(
            TAG,
            "onGetSession: pkg=${controllerInfo.packageName} " +
                "interfaceVersion=${controllerInfo.interfaceVersion} " +
                "sessionNotNull=${session != null}",
        )
        return session
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        mediaSession?.run {
            release()
            // Do NOT release the singleton ExoPlayer here — it is @Singleton scoped
            // and shared with SleepTimer for volume fade-out. Releasing it would
            // cause IllegalStateException("Player is released") on any subsequent
            // sleep timer operation or new PlayerViewModel instance.
        }
        mediaSession = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "LibravaultPlayback"

        /**
         * Notification channel id for the playback notification. Kept stable across releases
         * so the user's existing channel settings (importance, sound, vibration) are
         * preserved on app upgrade.
         */
        const val PLAYBACK_CHANNEL_ID = "libravault.playback"
    }
}