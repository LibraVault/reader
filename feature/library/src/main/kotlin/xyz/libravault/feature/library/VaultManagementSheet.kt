package xyz.libravault.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import xyz.libravault.core.domain.model.VaultFolder
import xyz.libravault.core.ui.theme.Dimens

// ── Vault management sheet ─────────────────────────────────────────────────────

@Composable
internal fun VaultManagementSheet(
    vaults: List<VaultFolder>,
    onAddVault: () -> Unit,
    onRemoveVault: (VaultFolder) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
    ) {
        vaults.forEach { vault ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceMd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = vault.displayName,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = vault.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onRemoveVault(vault) }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove vault",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        OutlinedButton(
            onClick = onAddVault,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add vault")
            Spacer(Modifier.size(Dimens.spaceSm))
            Text("Add vault")
        }
    }
}
