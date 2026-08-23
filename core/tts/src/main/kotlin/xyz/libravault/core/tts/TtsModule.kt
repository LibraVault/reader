package xyz.libravault.core.tts

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import xyz.libravault.core.tts.pocket.PocketTtsEngine
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TtsModule {

    @Binds
    @IntoMap
    @TtsEngineTypeKey(TtsEngineType.ANDROID)
    abstract fun bindAndroidEngine(impl: AndroidTtsEngine): TtsEngine

    @Binds
    @IntoMap
    @TtsEngineTypeKey(TtsEngineType.POCKET_TTS)
    abstract fun bindPocketEngine(impl: PocketTtsEngine): TtsEngine

    companion object {
        /**
         * Shared background scope for TTS work that outlives a single call -
         * [TtsEngineProvider]'s reactive engine switching,
         * [xyz.libravault.core.tts.pocket.PocketTtsEngine]'s model loading /
         * generation, and (from `core:cloudtts`) `CloudTtsEngine`'s
         * synthesis calls. Extracted as a binding (rather than each class
         * hardcoding `CoroutineScope(Dispatchers.Default)`) so tests can
         * substitute a `TestScope`.
         */
        @Provides
        @Singleton
        fun provideTtsCoroutineScope(): CoroutineScope = CoroutineScope(Dispatchers.Default)
    }
}
