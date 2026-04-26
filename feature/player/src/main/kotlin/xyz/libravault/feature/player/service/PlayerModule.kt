package xyz.libravault.feature.player.service

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {

    /**
     * Single ExoPlayer instance shared between [PlaybackService] and the UI.
     * Configured with:
     *  - CONTENT_TYPE_SPEECH for audiobooks (better focus handling)
     *  - handleAudioBecomingNoisy = true (pauses on headphone unplug)
     *  - handleAudioFocus = true (pauses on call, resumes after)
     */
    @Provides
    @Singleton
    fun provideExoPlayer(@ApplicationContext context: Context): ExoPlayer =
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

    /**
     * [MediaController] future — connects the UI to [PlaybackService].
     * The ViewModel resolves this future with retry logic before issuing
     * any playback commands.
     *
     * Note: this is intentionally NOT @Singleton — each ViewModel gets its own
     * connection to the service, avoiding stale/defunct controller futures that
     * cause permanent "Playback service unavailable" errors.
     */
    @Provides
    fun provideMediaControllerFuture(
        @ApplicationContext context: Context,
    ): ListenableFuture<MediaController> {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java),
        )
        return MediaController.Builder(context, sessionToken).buildAsync()
    }
}
