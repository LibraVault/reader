package xyz.libravault.feature.vault

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import xyz.libravault.core.storage.VaultScreenSecurityPreference

/**
 * Live read of [VaultScreenSecurityPreference] for `SecureScreenEffect`'s
 * `enabled` parameter — recomposes immediately when the setting changes
 * elsewhere (e.g. the Settings screen's toggle), unlike
 * `remember { VaultScreenSecurityPreference.isEnabled(context) }`, which only
 * reads the setting once per composition and previously left a stale value in
 * place until the caller left and re-entered the screen (issue #530 L5 / #569).
 */
@Composable
fun rememberScreenSecurityEnabled(context: Context): Boolean {
    val enabled by remember(context) { VaultScreenSecurityPreference.observe(context) }
        .collectAsState(initial = VaultScreenSecurityPreference.isEnabled(context))
    return enabled
}
