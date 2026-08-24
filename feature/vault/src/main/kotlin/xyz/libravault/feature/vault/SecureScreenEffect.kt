package xyz.libravault.feature.vault

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Applies `FLAG_SECURE` to the window for as long as [enabled] is true and
 * the calling composable is in composition — blocks screenshots, screen
 * recording, and non-secure display mirroring while it's showing. Cleared
 * on dispose (or when [enabled] flips to false), so leaving the screen, or
 * toggling the setting off, restores normal behavior immediately.
 *
 * Two call shapes, both from implementation plan §A.5/PRD §7.3:
 *  - `SecureScreenEffect()` (default `enabled = true`) — used
 *    **unconditionally**, not gated on the user's "Screen Security" setting,
 *    on the recovery-key display/entry steps ([CreateVaultScreen],
 *    [UnlockVaultScreen]'s recovery-key path): those must stay secure
 *    regardless of the toggle, since the recovery key is the one thing that
 *    can never be reconstructed if leaked.
 *  - `SecureScreenEffect(enabled = VaultScreenSecurityPreference.isEnabled(context))` —
 *    the general, toggle-driven use on vault content screens
 *    ([VaultContentsScreen], [VaultReaderScreen], [VaultPlayerScreen]).
 */
/**
 * Live read of [VaultScreenSecurityPreference] for [SecureScreenEffect]'s
 * `enabled` parameter — recomposes immediately when the setting changes
 * elsewhere (e.g. the Settings screen's toggle), unlike
 * `remember { VaultScreenSecurityPreference.isEnabled(context) }`, which only
 * reads the setting once per composition and previously left a stale value in
 * place until the caller left and re-entered the screen (issue #530 L5).
 */
@Composable
fun rememberScreenSecurityEnabled(context: Context): Boolean {
    val enabled by remember(context) { VaultScreenSecurityPreference.observe(context) }
        .collectAsState(initial = VaultScreenSecurityPreference.isEnabled(context))
    return enabled
}

@Composable
fun SecureScreenEffect(enabled: Boolean = true) {
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity, enabled) {
        if (enabled && activity != null) acquireSecureWindow(activity)
        onDispose {
            if (enabled && activity != null) releaseSecureWindow(activity)
        }
    }
}

/**
 * Outstanding secure-window requests per Activity.
 *
 * Reference counting is not incidental here. `addFlags`/`clearFlags` are not
 * paired operations on a counter — `clearFlags` unsets the bit outright — so
 * the naive version cleared `FLAG_SECURE` as soon as *any* one effect left
 * composition, even while another was still active.
 *
 * That is reachable on a plain navigation, not just in theory: going from
 * [VaultContentsScreen] to [VaultReaderScreen] or [VaultPlayerScreen] has both
 * destinations composed at once during the transition (both call this effect).
 * The incoming screen's effect runs first, then the outgoing screen's
 * `onDispose` cleared the flag — leaving the reader or player showing
 * decrypted vault content with screenshots and recents-thumbnail capture
 * silently re-enabled. Nothing on screen changes when that happens, which is
 * exactly why it survived five call sites with no tests
 * (docs/TEST_COVERAGE_PRD.md, S2).
 *
 * A [java.util.WeakHashMap] so a destroyed Activity cannot be retained by this
 * map if an effect is ever disposed out of order. No synchronization: Compose
 * runs effects on the applier (main) thread, so all access here is
 * single-threaded.
 */
private val secureWindowRequests = java.util.WeakHashMap<Activity, Int>()

private fun acquireSecureWindow(activity: Activity) {
    val count = (secureWindowRequests[activity] ?: 0) + 1
    secureWindowRequests[activity] = count
    if (count == 1) {
        activity.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

private fun releaseSecureWindow(activity: Activity) {
    val count = (secureWindowRequests[activity] ?: 1) - 1
    if (count <= 0) {
        secureWindowRequests.remove(activity)
        activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        secureWindowRequests[activity] = count
    }
}
