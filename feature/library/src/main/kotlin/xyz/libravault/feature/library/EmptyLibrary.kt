package xyz.libravault.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.libravault.core.ui.theme.Dimens
import xyz.libravault.feature.library.R

@Composable
internal fun EmptyLibrary(
    hasVaults: Boolean,
    onAddVault: () -> Unit,
    onRescan: () -> Unit,
) {
    val headlineRes = if (hasVaults) R.string.empty_headline_with_folders else R.string.empty_headline_no_folders
    val bodyRes = if (hasVaults) R.string.empty_library_with_folders else R.string.empty_library_no_folders
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
            modifier = Modifier.padding(horizontal = Dimens.spaceXl),
        ) {
            EmptyLibraryIllustration()
            Text(
                text = stringResource(headlineRes),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(bodyRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (hasVaults) {
                OutlinedButton(onClick = onRescan) {
                    Text(stringResource(R.string.empty_cta_rescan))
                }
            } else {
                Button(onClick = onAddVault) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(Dimens.spaceSm))
                    Text(stringResource(R.string.empty_cta_add_folder))
                }
            }
        }
    }
}

@Composable
private fun EmptyLibraryIllustration() {
    // Stylized open-vault glyph drawn with the new shape + colour tokens.
    // Avoids the need to ship an extra PNG while still feeling deliberate.
    Box(
        modifier = Modifier.size(96.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(96.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
    }
}
