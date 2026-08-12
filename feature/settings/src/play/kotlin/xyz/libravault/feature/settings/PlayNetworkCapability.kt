package xyz.libravault.feature.settings

import javax.inject.Inject
import javax.inject.Singleton

/** Play flavor: BTCPay donation verification talks to the network. */
@Singleton
class PlayNetworkCapability @Inject constructor() : NetworkCapability {
    override val hasNetwork = true
}
