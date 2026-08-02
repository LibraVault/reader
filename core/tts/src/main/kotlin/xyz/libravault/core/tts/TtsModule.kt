package xyz.libravault.core.tts

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TtsModule {

    @Binds
    @Singleton
    abstract fun bindTtsEngine(impl: AndroidTtsEngine): TtsEngine

    companion object {
        /**
         * Shared background scope for TTS work that outlives a single call -
         * [TtsEngineProvider]'s reactive engine switching and
         * [xyz.libravault.core.tts.pocket.PocketTtsEngine]'s model loading /
         * generation. Extracted as a binding (rather than each class
         * hardcoding `CoroutineScope(Dispatchers.Default)`) so tests can
         * substitute a `TestScope`.
         */
        @Provides
        @Singleton
        fun provideTtsCoroutineScope(): CoroutineScope = CoroutineScope(Dispatchers.Default)
    }
}
