package xyz.libravault.feature.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Every registered Encrypted Vault, locked or unlocked. Tapping an unlocked
 * vault opens it directly ([onOpenVault]); tapping a locked one goes to
 * [UnlockVaultScreen] first ([onUnlockVault]) — this screen itself never
 * touches key material, only [VaultListItemUiState.isUnlocked].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultListScreen(
    onCreateVault: () -> Unit,
    onUnlockVault: (vaultId: String) -> Unit,
    onOpenVault: (vaultId: String) -> Unit,
    onBack: () -> Unit,
    viewModel: VaultListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    if (state.showExplainer) {
        FolderVsVaultExplainerDialog(onDismiss = viewModel::dismissExplainer)
    }

    // Locked/unlocked state can drift while this screen isn't the front-most
    // destination (see VaultListViewModel's doc comment) — refresh every time
    // navigation returns here, not just once on first composition.
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentViewModel = rememberUpdatedState(viewModel)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) currentViewModel.value.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vaults") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateVault) {
                Icon(Icons.Filled.Add, contentDescription = "New Vault")
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                state.vaults.isEmpty() -> EmptyState(onCreateVault, modifier = Modifier.align(Alignment.Center))
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.vaults, key = { it.id }) { vault ->
                        VaultRow(
                            vault = vault,
                            onClick = {
                                if (vault.isUnlocked) onOpenVault(vault.id) else onUnlockVault(vault.id)
                            },
                            onLock = { viewModel.lock(vault.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shown once, the first time the user opens this screen (PRD §9's
 * naming decision) — explains that "Vault" (this screen, encrypted, PIN-
 * protected) and "Folder" (the unencrypted SAF-tree concept, shown
 * elsewhere in the app) are two different guarantees, not two names for
 * the same thing.
 */
@Composable
private fun FolderVsVaultExplainerDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Folders and Vaults are different") },
        text = {
            Text(
                "A Folder is a location you point LibraVault at — its files stay exactly " +
                    "where they are, unencrypted, same as any file manager.\n\n" +
                    "A Vault is different: files you import are encrypted and stored inside " +
                    "LibraVault itself, unreadable without its PIN — even with direct access " +
                    "to this device's storage.",
            )
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Got it") }
        },
    )
}

@Composable
private fun EmptyState(onCreateVault: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "No Vaults yet. A Vault keeps files encrypted at rest, behind a PIN.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onCreateVault) { Text("Create a Vault") }
    }
}

@Composable
private fun VaultRow(vault: VaultListItemUiState, onClick: () -> Unit, onLock: () -> Unit) {
    ListItem(
        headlineContent = { Text(vault.displayName) },
        supportingContent = { Text(if (vault.isUnlocked) "Unlocked" else "Locked") },
        leadingContent = {
            Icon(
                if (vault.isUnlocked) Icons.Filled.LockOpen else Icons.Filled.Lock,
                contentDescription = null,
            )
        },
        trailingContent = {
            if (vault.isUnlocked) {
                IconButton(onClick = onLock) {
                    Icon(Icons.Filled.Lock, contentDescription = "Lock now")
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}
