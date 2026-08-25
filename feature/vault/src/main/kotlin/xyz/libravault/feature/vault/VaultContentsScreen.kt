package xyz.libravault.feature.vault

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import xyz.libravault.core.ui.SecureScreenEffect
import xyz.libravault.core.vaultstore.VaultManifestEntry
import xyz.libravault.core.vaultstore.toHexString

/**
 * One unlocked vault's contents: browse ([onOpenEntry]), import (the FAB,
 * Phase 5b — [ActivityResultContracts.OpenMultipleDocuments], no persisted
 * URI permission requested since content is read once and immediately
 * encrypted into the vault, never re-read from the source later). Locking
 * (explicitly, or via auto-lock firing while this screen is in front) pops
 * back to [VaultListScreen] rather than showing a stale list.
 *
 * `FLAG_SECURE` applies here (Phase 5c, PRD §7.3) — even just the list of
 * titles/authors is content someone encrypted a vault specifically to hide,
 * not merely the reader/player screens that show it in full.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultContentsScreen(
    onBack: () -> Unit,
    onOpenEntry: (VaultManifestEntry) -> Unit,
    viewModel: VaultContentsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    SecureScreenEffect(enabled = rememberScreenSecurityEnabled(context))

    LaunchedEffect(state.wasLocked) {
        if (state.wasLocked) onBack()
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.onFilesPicked(uris) }

    if (state.importItems.isNotEmpty()) {
        ImportProgressSheet(items = state.importItems, onDismiss = viewModel::dismissImportSummary)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.displayName.ifBlank { "Vault" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.lock(); onBack() }) {
                        Icon(Icons.Filled.Lock, contentDescription = "Lock")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                Icon(Icons.Filled.Add, contentDescription = "Import files")
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                state.entries.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "This Vault is empty. Tap + to import files.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.entries, key = { it.fileId.toHexString() }) { entry ->
                        ListItem(
                            leadingContent = {
                                VaultEntryThumbnail(entry = entry, coverJpeg = state.coverArt[entry.fileId.toHexString()])
                            },
                            headlineContent = { Text(entry.title) },
                            supportingContent = entry.author?.let { { Text(it) } },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenEntry(entry) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportProgressSheet(items: List<ImportItemUiState>, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    val allSettled = items.all { it.status == ImportItemStatus.DONE || it.status == ImportItemStatus.ERROR }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Importing", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            items.forEach { item ->
                ListItem(
                    headlineContent = { Text(item.displayName) },
                    supportingContent = item.errorMessage?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    trailingContent = { ImportStatusIcon(item.status) },
                )
            }
            if (allSettled) {
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
        }
    }
}

/** A single vault entry's row thumbnail (issue #169). [coverJpeg] is decrypted,
 * already-downsampled JPEG bytes from [VaultContentsViewModel]'s cover-art
 * map — decoded to a bitmap here, in Compose, rather than in the ViewModel,
 * so the plaintext `Bitmap` never outlives this composition and is never
 * written anywhere. A `null`/failed-to-decode cover falls back to
 * [VaultCoverPlaceholder], the padlock-badged "no cover" treatment. */
@Composable
private fun VaultEntryThumbnail(entry: VaultManifestEntry, coverJpeg: ByteArray?) {
    val bitmap = remember(coverJpeg) {
        coverJpeg?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
    }
    Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp))) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = entry.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            VaultCoverPlaceholder(title = entry.title, format = entry.format, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ImportStatusIcon(status: ImportItemStatus) {
    when (status) {
        ImportItemStatus.PENDING, ImportItemStatus.IMPORTING ->
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        ImportItemStatus.DONE ->
            Icon(Icons.Filled.Check, contentDescription = "Imported", tint = MaterialTheme.colorScheme.primary)
        ImportItemStatus.ERROR ->
            Icon(Icons.Filled.Error, contentDescription = "Failed", tint = MaterialTheme.colorScheme.error)
    }
}
