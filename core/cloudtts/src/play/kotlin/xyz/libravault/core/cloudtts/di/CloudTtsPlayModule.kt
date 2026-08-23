package xyz.libravault.core.cloudtts.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import xyz.libravault.core.cloudtts.CloudTtsProvider
import xyz.libravault.core.cloudtts.RealCloudTtsProvider
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CloudTtsPlayModule {

    @Binds
    @Singleton
    abstract fun bindCloudTtsProvider(impl: RealCloudTtsProvider): CloudTtsProvider

    companion object {
        /** One shared client for all five vendor adapters — connection
         * pooling, one place to tune timeouts. Synthesis calls can be slow
         * (real vendor TTS latency), so a generous read timeout; connect/write
         * stay short so a genuinely unreachable host fails fast instead of
         * hanging the fallback-to-on-device path (engine-wiring follow-up). */
        @Provides
        @Singleton
        fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
