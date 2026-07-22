package xyz.libravault.core.licensing

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LicensingModule {

    @Provides
    @Singleton
    fun provideProGate(@ApplicationContext context: Context): IProGate =
        PlayBillingProGate(context)
}
