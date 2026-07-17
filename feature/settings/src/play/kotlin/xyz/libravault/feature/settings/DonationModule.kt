package xyz.libravault.feature.settings

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DonationModule {
    @Binds
    @Singleton
    abstract fun bindDonationClient(client: BtcPayClient): DonationClient

    @Binds
    @Singleton
    abstract fun bindStaticAddresses(impl: EmptyStaticDonationAddresses): StaticDonationAddresses
}
