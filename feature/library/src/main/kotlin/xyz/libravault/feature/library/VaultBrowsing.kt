package xyz.libravault.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.libravault.core.domain.model.VaultFolder
import xyz.libravault.core.ui.theme.Dimens

// ── Vault browsing components ──────────────────────────────────────────────────

@Composable
internal fun VaultFilterChips(
    vaults: List<VaultFolder>,
    selectedVaultId: Long?,
    onSelectVault: (Long) -> Unit,
    onShowAll: () -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
    ) {
        item {
            FilterChip(
                selected = selectedVaultId == null,
                onClick = onShowAll,
                label = { Text("All") },
            )
        }
        items(vaults, key = { it.id }) { vault ->
            FilterChip(
                selected = vault.id == selectedVaultId,
                onClick = { onSelectVault(vault.id) },
                label = { Text(vault.displayName) },
                leadingIcon = {
                    Icon(Icons.Default.Folder, contentDescription = "Folder", modifier = Modifier.size(Dimens.spaceLg))
                },
            )
        }
    }
}

@Composable
internal fun VaultSectionHeader(
    vault: VaultFolder,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Dimens.spaceSm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
        ) {
            Icon(imageVector = Icons.Default.Folder, contentDescription = "Folder", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(
                text = vault.displayName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        TextButton(onClick = onClick) {
            Text("View all")
        }
    }
}
