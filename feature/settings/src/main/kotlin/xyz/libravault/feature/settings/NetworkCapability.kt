package xyz.libravault.feature.settings

/**
 * Whether this build flavor ever makes a network call, for any reason.
 *
 * Used by the About screen to describe the app's network behavior accurately per
 * flavor, rather than the old one-size-fits-all copy that described BTCPay/internet
 * donation verification even on the fdroid build, which has no `INTERNET` permission
 * and makes no network calls at all. Implemented by a Hilt-injected flavor-specific
 * provider, the same pattern as [StaticDonationAddresses]:
 *  - fdroid sourceSet: `false` — no network calls anywhere in the app.
 *  - play sourceSet: `true` — BTCPay donation verification talks to the network.
 */
interface NetworkCapability {
    val hasNetwork: Boolean
}
