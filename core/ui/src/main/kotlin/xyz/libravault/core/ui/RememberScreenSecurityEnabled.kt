package xyz.libravault.core.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import xyz.libravault.core.storage.VaultScreenSecurityPreference

/**
 * Live read of [VaultScreenSecurityPreference] for [SecureScreenEffect]'s
 * `enabled` parameter — recomposes immediately when the setting changes
 * elsewhere (e.g. the Settings screen's toggle), unlike
 * `remember { VaultScreenSecurityPreference.isEnabled(context) }`, which only
 * reads the setting once per composition and previously left a stale value in
 * place until the caller left and re-entered the screen (issue #530 L5 / #569).
 *
 * Moved here from `feature:vault` by #493 — `feature:reader`'s `ReaderScreen`
 * and `feature:player`'s `PlayerScreen` need this too (both gate
 * [SecureScreenEffect] on a [ContentSource.VaultEntry][
 * xyz.libravault.core.domain.model.ContentSource.VaultEntry]-backed item the
 * same way `feature:vault`'s screens already did), and this composable has no
 * vault-specific dependency beyond [VaultScreenSecurityPreference], which
 * already lives in `core:storage`.
 */
@Composable
fun rememberScreenSecurityEnabled(context: Context): Boolean {
    val enabled by remember(context) { VaultScreenSecurityPreference.observe(context) }
        .collectAsState(initial = VaultScreenSecurityPreference.isEnabled(context))
    return enabled
}
