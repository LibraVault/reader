package xyz.libravault.feature.settings

import javax.inject.Inject
import javax.inject.Singleton

/**
 * F-Droid flavor implementation of [StaticDonationAddresses].
 *
 * These addresses only ship in the F-Droid APK. The Play flavor provides
 * an empty [StaticDonationAddresses] via its own `EmptyStaticDonationAddresses`
 * class in `src/play/.../EmptyStaticDonationAddresses.kt`, so the Play APK
 * never contains the F-Droid fallback strings.
 *
 * To rotate the addresses, edit the two constants below and ship a new
 * F-Droid release. No migration is required on the user's side — these are
 * reference-only values the user copies out of the donation screen.
 */
@Singleton
class FdroidStaticDonationAddresses @Inject constructor() : StaticDonationAddresses {
    override val btc: String = "bc1q9y4q49lxnwrt9pnkgrxfpq92s9mvwv9espc5yg"
    override val xmr: String = "48LTe9fEF311sJ1syhC9oD8VcNqfjsLAo8WcmXYC8iJwg24cM6R2mydXSnQ18N2Q2jLU8qtc26rrpadUra6DDiTW82eVXWm"
}