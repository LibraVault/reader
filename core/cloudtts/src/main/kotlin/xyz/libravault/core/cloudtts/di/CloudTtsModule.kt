package xyz.libravault.core.cloudtts.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import xyz.libravault.core.cloudtts.CloudApiKeyStore
import xyz.libravault.core.cloudtts.CloudPlayback
import xyz.libravault.core.cloudtts.CloudTtsEngine
import xyz.libravault.core.cloudtts.MediaPlayerCloudPlayback
import xyz.libravault.core.cloudtts.RealCloudApiKeyStore
import xyz.libravault.core.tts.TtsEngine
import xyz.libravault.core.tts.TtsEngineType
import xyz.libravault.core.tts.TtsEngineTypeKey
import javax.inject.Singleton

/**
 * Unflavored bindings — [CloudApiKeyStore] has no fdroid/play split (Android
 * Keystore isn't a networking dependency, see core/cloudtts/build.gradle.kts'
 * module comment). The [xyz.libravault.core.cloudtts.CloudTtsProvider]
 * binding (real vendor dispatch vs. NoOp) is the one thing that IS
 * flavor-split — see `di/CloudTtsPlayModule.kt` and `di/CloudTtsFdroidModule.kt`.
 *
 * [CloudTtsEngine]'s `@IntoMap` contribution here is what makes `core:tts`'s
 * `TtsEngineFactory` able to resolve `TtsEngineType.CLOUD` without `core:tts`
 * ever compiling against `core:cloudtts` — see `TtsEngineTypeKey`'s class doc.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CloudTtsModule {

    @Binds
    @Singleton
    abstract fun bindCloudApiKeyStore(impl: RealCloudApiKeyStore): CloudApiKeyStore

    @Binds
    @Singleton
    abstract fun bindCloudPlayback(impl: MediaPlayerCloudPlayback): CloudPlayback

    @Binds
    @IntoMap
    @TtsEngineTypeKey(TtsEngineType.CLOUD)
    abstract fun bindCloudTtsEngine(impl: CloudTtsEngine): TtsEngine
}
