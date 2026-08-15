package xyz.libravault.feature.settings

/**
 * Where "Support the Project" sends users — identical on every flavor and
 * platform (Play, F-Droid, iOS).
 *
 * Apple rejects apps that show crypto donation addresses/QR codes inside the
 * app's own UI (unapproved tipping / IAP bypass). The compliant fix, applied
 * consistently rather than only where Apple happens to enforce it, is to never
 * render an address or QR code in-app anywhere and instead hand off to this
 * page — which is free to show BTC/XMR addresses since it isn't inside the app
 * binary. This also means this app makes zero network calls of any kind: the
 * BTCPay invoice-creation/polling flow this replaced (see git history for
 * `BtcPayClient`/`StaticDonationClient`) is gone, not just hidden.
 *
 * Explicit `.html` extension rather than the extensionless `/support` used
 * elsewhere on the site (sitemap.xml, nav links) — those rely on the static
 * host's clean-URL rewriting, which this link has no way to verify at build
 * time. The `.html` file always resolves regardless of hosting config.
 */
internal const val SUPPORT_URL = "https://libravault.xyz/support.html"
