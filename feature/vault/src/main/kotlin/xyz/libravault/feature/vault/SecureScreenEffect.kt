package xyz.libravault.feature.vault

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Applies `FLAG_SECURE` to the window for as long as the calling composable
 * is in composition — blocks screenshots, screen recording, and non-secure
 * display mirroring while it's showing. Cleared on dispose, so leaving the
 * screen (back, navigating away) restores normal behavior immediately.
 *
 * Used **unconditionally** (not gated on the user's "Screen Security"
 * setting) on the recovery-key display step of [CreateVaultScreen] —
 * implementation plan §A.5: that screen must be secure regardless of the
 * toggle, since it's the one and only time the recovery key is ever shown.
 * The general, toggle-driven use of this same effect on reader/player
 * content is Phase 5c's job, not this one's.
 */
@Composable
fun SecureScreenEffect() {
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
