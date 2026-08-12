package xyz.libravault.feature.settings

import javax.inject.Inject
import javax.inject.Singleton

/** F-Droid flavor: no network calls anywhere in the app. */
@Singleton
class FdroidNetworkCapability @Inject constructor() : NetworkCapability {
    override val hasNetwork = false
}
