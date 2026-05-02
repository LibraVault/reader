package xyz.libravault.core.licensing

/**
 * Single source of truth for Pro-tier configuration.
 *
 * SHOW_PURCHASE_LINK: set to true in the Play Store build flavour so the
 * "Get Pro" button appears and opens the system browser to PURCHASE_URL.
 * For the F-Droid / direct-download build this stays false — F-Droid policy
 * prohibits in-app payment links, but the activation screen itself is fine.
 *
 * TODO: wire SHOW_PURCHASE_LINK to a BuildConfig field injected by the play
 * product flavour when adding Play Store support.
 */
object ProConfig {
    const val PURCHASE_URL = "https://libravault.xyz/pro"
    const val RECOVERY_BASE_URL = "https://recovery.libravault.xyz/"
    const val SHOW_PURCHASE_LINK = false
}
