package xyz.libravault.feature.settings

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Play flavor implementation of [StaticDonationAddresses]. Always returns
 * empty strings — the Play flavor routes donations through BTCPay via
 * [BtcPayClient] and never falls back to the F-Droid static addresses.
 *
 * Keeping this binding (instead of leaving the interface unbound in Play)
 * means SettingsViewModel can inject [StaticDonationAddresses] without
 * needing flavor-conditional code.
 */
@Singleton
class EmptyStaticDonationAddresses @Inject constructor() : StaticDonationAddresses {
    override val btc: String = ""
    override val xmr: String = ""
}