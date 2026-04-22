package xyz.libravault.core.logger.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object LoggerModule
// LibravaultLogger is @Singleton + @Inject constructor — no manual bindings needed.
