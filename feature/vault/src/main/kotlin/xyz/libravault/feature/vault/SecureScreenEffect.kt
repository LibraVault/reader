package xyz.libravault.feature.vault

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
@Composable
fun SecureScreenEffect(enabled: Boolean = true) {
    val activity = LocalContext.current as? Activity
    DisposableEffect(enabled) {
        if (enabled) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (enabled) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
