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
     *
     * Seek increments are seeded from the user's `defaultSkipDurationSec` setting.
     * Because ExoPlayer's increment is immutable after build, runtime setting
     * changes are honored by the UI layer and (where applicable) media-session
     * callbacks — both read [SkipDurationPreference] directly. The legacy
     * `MediaController.seekBack`/`seekForward` transport commands used by the
     * lockscreen / Quick-Settings compact strip use this initial value.
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
            .setSeekBackIncrementMs(SkipDurationPreference.getSkipDurationMs(context))
            .setSeekForwardIncrementMs(SkipDurationPreference.getSkipDurationMs(context))
            .build()

    /**
     * Singleton [MediaController] future — connects the UI to [PlaybackService].
     * The ViewModel uses the non-blocking addListener() pattern to resolve it,
     * and retries on failure with exponential backoff.
     */
    @Provides
    @Singleton
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
