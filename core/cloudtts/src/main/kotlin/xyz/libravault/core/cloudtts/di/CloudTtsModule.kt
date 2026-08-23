package xyz.libravault.core.cloudtts.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import xyz.libravault.core.cloudtts.CloudApiKeyStore
import xyz.libravault.core.cloudtts.RealCloudApiKeyStore
import javax.inject.Singleton

/**
 * Unflavored bindings — [CloudApiKeyStore] has no fdroid/play split (Android
 * Keystore isn't a networking dependency, see core/cloudtts/build.gradle.kts'
 * module comment). The [xyz.libravault.core.cloudtts.CloudTtsProvider]
 * binding (real vendor dispatch vs. NoOp) is the one thing that IS
 * flavor-split — see `di/CloudTtsPlayModule.kt` (vendor-adapters follow-up)
 * and `di/CloudTtsFdroidModule.kt`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CloudTtsModule {

    @Binds
    @Singleton
    abstract fun bindCloudApiKeyStore(impl: RealCloudApiKeyStore): CloudApiKeyStore
}
