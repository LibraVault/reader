package xyz.libravault.core.storage.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object StorageModule
// VaultManager and FileScanner are @Singleton + @Inject constructor — no manual bindings needed.
