package xyz.libravault.core.cloudtts.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import xyz.libravault.core.cloudtts.CloudTtsProvider
import xyz.libravault.core.cloudtts.NoOpCloudTtsProvider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CloudTtsFdroidModule {

    @Binds
    @Singleton
    abstract fun bindCloudTtsProvider(impl: NoOpCloudTtsProvider): CloudTtsProvider
}
