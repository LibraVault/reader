@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package xyz.libravault.feature.player.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import xyz.libravault.core.domain.usecase.GetAdjacentLibraryItemUseCase
import xyz.libravault.core.domain.usecase.GetListeningProgressUseCase
import xyz.libravault.core.domain.usecase.SaveListeningProgressUseCase
import xyz.libravault.feature.player.R

/**
 * Foreground [MediaSessionService] — keeps audio playing when backgrounded or screen locked.
 *
 * # Notification + lockscreen tile plumbing
 *
 * - **Notification provider**: stock [DefaultMediaNotificationProvider]. The small icon
 *   is set via the `androidx.media3.session.SMALL_ICON_RESOURCE` `<meta-data>` entry on
 *   `<application>` in `AndroidManifest.xml` — that's the documented Media3 way to set
 *   the small icon without subclassing the provider. We deliberately don't subclass it.
 *
 * - **Lockscreen / Quick-Settings tile (Android 13+)**: populated by [LibravaultMediaCallback]
 *   via [MediaSession.Callback.onConnect]. See that class for the full rationale. The
 *   callback publishes a 5-button strip `[Prev | −seek | PlayPause | +seek | Next]` to
 *   `MediaSession.customLayout` as `PlaybackStateCompat.customActions`, and removes the
 *   overlapping standard commands from `availablePlayerCommands` so the system tile doesn't
 *   also derive duplicate standard-actions buttons for the same controls. Prev/Next switch
 *   to the previous/next sibling audio file in the current item's vault folder — see
 *   [LibravaultMediaCallback]'s KDoc for why (the callback runs in the playback service and
 *   has no access to the in-app player's chapter list, so file-switching is the practical
 *   "next chapter" on the lockscreen tile).
 *
 * - **Skip duration**: read from [SkipDurationPreference] at service-create time and
 *   passed into [LibravaultMediaCallback] so the ±seek tile buttons embed the correct
 *   offset magnitude in their [android.os.Bundle]. The ExoPlayer's
 *   `seekBackIncrementMs` / `seekForwardIncrementMs` are seeded from the same source in
 *   [PlayerModule.provideExoPlayer], keeping the tile, the player transport commands,
 *   and the in-app ±seek buttons in sync at app start.
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
    private var mediaCallback: LibravaultMediaCallback? = null

    @Inject
    lateinit var player: ExoPlayer

    @Inject
    lateinit var playbackStateHolder: PlaybackStateHolder

    @Inject
    lateinit var getAdjacentItem: GetAdjacentLibraryItemUseCase

    @Inject
    lateinit var getListeningProgress: GetListeningProgressUseCase

    @Inject
    lateinit var saveListeningProgress: SaveListeningProgressUseCase

    /**
     * Encrypted Vault stop-on-lock (#493, scope decision 2 — required for correctness,
     * not opinionated): [VaultDataSource][xyz.libravault.core.vaultcontent.VaultDataSource]
     * has no cross-thread signal from a locking `VaultStore` to an already-playing
     * `MediaSource` — once [xyz.libravault.core.vaultstore.VaultSessionManager]'s own
     * `onStop()` observer zeroes the VMK, the next mid-stream read throws and surfaces
     * as a raw, unpredictable player error. Pausing proactively on the same
     * app-backgrounded signal avoids that race. Ordering relative to
     * `VaultSessionManager`'s own observer doesn't matter — `player.pause()` is safe
     * regardless of whether the VMK has been zeroed yet. A no-op for a real-file item
     * (`vaultEntry == null`).
     */
    private val vaultAutoStopObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            if (playbackStateHolder.state.value.vaultEntry != null) {
                Log.i(TAG, "onStop: app backgrounded with vault audio active — pausing")
                player.pause()
            }
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate: start")
        ProcessLifecycleOwner.get().lifecycle.addObserver(vaultAutoStopObserver)

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

        val seekStepMs = SkipDurationPreference.getSkipDurationMs(this)

        try {
            val callback = LibravaultMediaCallback(
                context = this,
                player = player,
                seekStepMs = seekStepMs,
                playbackStateHolder = playbackStateHolder,
                getAdjacentItem = getAdjacentItem,
                getListeningProgress = getListeningProgress,
                saveListeningProgress = saveListeningProgress,
            )
            mediaCallback = callback
            val builder = MediaSession.Builder(this, player)
                .setCallback(callback)
            sessionActivity?.let { builder.setSessionActivity(it) }
            mediaSession = builder.build()
            Log.i(TAG, "onCreate: MediaSession built; id=${mediaSession?.id} seekStepMs=$seekStepMs")
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
        // Required — ProcessLifecycleOwner is process-wide, not service-scoped; without
        // this, repeated service recreation would leak observers onto it.
        ProcessLifecycleOwner.get().lifecycle.removeObserver(vaultAutoStopObserver)
        mediaCallback?.release()
        mediaCallback = null
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
        const val TAG = "LibravaultPlaybackService"

        /**
         * Notification channel id for the playback notification. Kept stable across releases
         * so the user's existing channel settings (importance, sound, vibration) are
         * preserved on app upgrade.
         */
        const val PLAYBACK_CHANNEL_ID = "libravault.playback"
    }
}