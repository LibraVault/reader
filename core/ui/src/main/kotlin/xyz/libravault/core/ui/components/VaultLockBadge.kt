package xyz.libravault.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Small padlock badge marking a cover as belonging to an Encrypted Vault item.
 *
 * Extracted from `feature:vault`'s `VaultCoverPlaceholder` (#169/#308) into
 * `core:ui` (Phase 3, #508) — `feature:library`'s `LibraryItemCard` needs the
 * identical badge once vault items can appear in the main Library list, and
 * `core:ui` is already a dependency of every feature module (see
 * `AndroidFeatureConventionPlugin`), so no new module edge is needed.
 * `VaultCoverPlaceholder` now calls this composable instead of inlining its
 * own copy — pure refactor, no behavior change there.
 */
@Composable
fun VaultLockBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(16.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = "Encrypted vault item",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(10.dp),
            )
        }
    }
}
