package xyz.libravault.feature.library

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.core.domain.model.VaultFolder
import xyz.libravault.feature.player.service.PlaybackStateHolder
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onItemClick: (LibraryItem) -> Unit,
    onSettingsClick: () -> Unit,
    onNowPlayingClick: (Long) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    var isSearchOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // ── Vault management state ────────────────────────────────────────────────
    var vaultToRemove by remember { mutableStateOf<VaultFolder?>(null) }
    var showAddVaultSheet by remember { mutableStateOf(false) }

    // SAF folder picker launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri = result.data?.data ?: return@rememberLauncherForActivityResult
            val displayName = uri.lastPathSegment
                ?.substringAfterLast(':')
                ?.substringAfterLast('/')
                ?: "My Vault"
            viewModel.onVaultPicked(uri, displayName)
        }
    }

    fun launchFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        folderPickerLauncher.launch(intent)
    }

    // ── Remove vault confirmation dialog ──────────────────────────────────────
    vaultToRemove?.let { vault ->
        AlertDialog(
            onDismissRequest = { vaultToRemove = null },
            title = { Text("Remove vault?") },
            text = {
                Text("This will remove \"${vault.displayName}\" and all its items from the library.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeVault(vault)
                    vaultToRemove = null
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { vaultToRemove = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Show stale file snackbar
    LaunchedEffect(state.staleItemMessage) {
        if (state.staleItemMessage) {
            snackbarHost.showSnackbar(
                message = "File not found — it may have been moved or deleted. " +
                        "It will be removed on the next scan.",
            )
            viewModel.dismissStaleMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        val vault = state.selectedVault
                        if (vault != null) {
                            Text(
                                text = vault.displayName,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Text(
                                text = "LibraVault",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    navigationIcon = {
                        if (state.selectedVault != null) {
                            IconButton(onClick = viewModel::clearVaultFilter) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "All vaults",
                                )
                            }
                        }
                    },
                    actions = {
                        if (state.selectedVault == null) {
                            IconButton(onClick = { isSearchOpen = true }) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                            IconButton(onClick = { showAddVaultSheet = !showAddVaultSheet }) {
                                Icon(Icons.Default.Add, contentDescription = "Add vault")
                            }
                        }
                        IconButton(onClick = onSettingsClick) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
                // Scan progress indicator — subtle, non-blocking
                if (state.isScanning) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
        },
        bottomBar = {
            if (nowPlaying.itemId != null) {
                MiniPlayerBar(
                    title = nowPlaying.title,
                    author = nowPlaying.author,
                    coverArtPath = nowPlaying.coverArtPath,
                    isPlaying = nowPlaying.isPlaying,
                    onClick = { nowPlaying.itemId?.let(onNowPlayingClick) },
                )
            }
        },
    ) { innerPadding ->

        PullToRefreshBox(
            isRefreshing = state.isScanning,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (state.allItems.isEmpty() && !state.isScanning) {
                EmptyLibrary(hasVaults = state.vaults.isNotEmpty())
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {

                    // ── Vault management sheet (add/remove) ──────────────────────
                    if (showAddVaultSheet) {
                        item {
                            VaultManagementSheet(
                                vaults = state.vaults,
                                onAddVault = { launchFolderPicker() },
                                onRemoveVault = { vaultToRemove = it },
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    // ── Continue cards — compact row, up to 3 side by side ────
                    val continueItems = listOfNotNull(state.currentBook, state.currentAudiobook)
                    if (continueItems.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                continueItems.forEach { item ->
                                    ContinueCard(
                                        item    = item,
                                        onClick = {
                                            if (viewModel.validateItem(item)) onItemClick(item)
                                            else viewModel.showStaleMessage()
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                // Phantom weight to keep cards left-aligned when only one item
                                if (continueItems.size == 1) {
                                    Spacer(Modifier.weight(2f))
                                }
                            }
                            Spacer(Modifier.height(20.dp))
                        }
                    }

                    // ── Vault filter chips (only when not already filtered) ────
                    if (state.vaults.size > 1 && state.selectedVault == null && state.searchResults == null) {
                        item {
                            VaultFilterChips(
                                vaults = state.vaults,
                                selectedVaultId = state.selectedVault?.id,
                                onSelectVault = viewModel::selectVault,
                                onShowAll = viewModel::clearVaultFilter,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    // ── Library content ──────────────────────────────────────────
                    if (state.searchResults != null) {
                        // Search results view — flat list
                        val items = state.searchResults!!
                        item {
                            SectionHeader("Results for \"${state.searchQuery}\"")
                        }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(items, key = { it.id }) { item ->
                                    LibraryItemCard(
                                        item = item,
                                        onClick = {
                                            if (viewModel.validateItem(item)) onItemClick(item)
                                            else viewModel.showStaleMessage()
                                        },
                                    )
                                }
                            }
                        }
                    } else if (state.selectedVault != null) {
                        // Single vault view — header shows vault name, items below
                        val vaultItems = viewModel.vaultFilteredItems(
                            state.vaultGroupedItems,
                            state.selectedVault.id,
                        )
                        item {
                            SectionHeader("All items")
                        }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(vaultItems, key = { it.id }) { item ->
                                    LibraryItemCard(
                                        item = item,
                                        onClick = {
                                            if (viewModel.validateItem(item)) onItemClick(item)
                                            else viewModel.showStaleMessage()
                                        },
                                    )
                                }
                            }
                        }
                    } else {
                        // All vaults view — grouped by vault folder
                        state.vaultGroupedItems.forEach { (vault, vaultItems) ->
                            item {
                                VaultSectionHeader(
                                    vault = vault,
                                    onClick = { viewModel.selectVault(vault.id) },
                                )
                            }
                            item {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    items(vaultItems, key = { it.id }) { item ->
                                        LibraryItemCard(
                                            item = item,
                                            onClick = {
                                                if (viewModel.validateItem(item)) onItemClick(item)
                                                else viewModel.showStaleMessage()
                                            },
                                        )
                                    }
                                }
                            }
                            item { Spacer(Modifier.height(8.dp)) }
                        }
                    }
                }
            }
        }

        // ── Search overlay ─────────────────────────────────────────────────────
        if (isSearchOpen) {
            SearchBar(
                query = state.searchQuery,
                onQueryChange = viewModel::onSearchQueryChanged,
                onSearch = { },
                active = true,
                onActiveChange = { active ->
                    if (!active) { isSearchOpen = false; viewModel.clearSearch() }
                },
                placeholder = { Text("Search title, author, narrator, series…") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                val results = state.searchResults
                if (!results.isNullOrEmpty()) {
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(results, key = { it.id }) { item ->
                            SearchResultRow(
                                item = item,
                                onClick = {
                                    isSearchOpen = false
                                    viewModel.clearSearch()
                                    if (viewModel.validateItem(item)) onItemClick(item)
                                    else viewModel.showStaleMessage()
                                },
                            )
                        }
                    }
                } else if (state.searchQuery.isNotBlank()) {
                    Box(
                        Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No results for \"${state.searchQuery}\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ── Vault management sheet ─────────────────────────────────────────────────────

@Composable
private fun VaultManagementSheet(
    vaults: List<VaultFolder>,
    onAddVault: () -> Unit,
    onRemoveVault: (VaultFolder) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        vaults.forEach { vault ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
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
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Add vault")
        }
    }
}

// ── Vault browsing components ──────────────────────────────────────────────────

@Composable
private fun VaultFilterChips(
    vaults: List<VaultFolder>,
    selectedVaultId: Long?,
    onSelectVault: (Long) -> Unit,
    onShowAll: () -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun VaultSectionHeader(
    vault: VaultFolder,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
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

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun EmptyLibrary(hasVaults: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = if (hasVaults) "Your vault is empty.\nAdd some books or audio files to get started."
            else "No vaults set up yet.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ContinueCard(
    item: LibraryItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Box {
            // Cover art — portrait aspect ratio
            if (item.coverArtPath != null) {
                AsyncImage(
                    model = item.coverArtPath,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f),
                )
            } else {
                // Fallback placeholder when no cover art
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (item.format in listOf(
                            MediaFormat.MP3,
                            MediaFormat.M4B,
                            MediaFormat.OGG,
                            MediaFormat.FLAC,
                            MediaFormat.OPUS,
                            MediaFormat.AAC,
                        )) Icons.Default.Headphones else Icons.Default.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            // Resume icon — bottom-left overlay with dark scrim
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .background(
                        color = Color.Black.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(topEnd = 8.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Resume",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // Title below the art
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LibraryItemCard(item: LibraryItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier.width(120.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (item.coverArtPath != null) {
                    AsyncImage(
                        model = item.coverArtPath,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = if (item.format in listOf(
                                MediaFormat.MP3, MediaFormat.M4B,
                                MediaFormat.OGG, MediaFormat.FLAC,
                                MediaFormat.OPUS, MediaFormat.AAC,
                            )
                        ) Icons.Default.Headphones else Icons.Default.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchResultRow(item: LibraryItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (item.format in listOf(
                    MediaFormat.MP3, MediaFormat.M4B,
                    MediaFormat.OGG, MediaFormat.FLAC,
                    MediaFormat.OPUS, MediaFormat.AAC,
                )
            ) Icons.Default.Headphones else Icons.Default.MenuBook,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.author,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Mini-player bar ───────────────────────────────────────────────────────────

@Composable
private fun MiniPlayerBar(
    title: String,
    author: String,
    coverArtPath: String?,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Small cover art
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (coverArtPath != null) {
                    AsyncImage(
                        model = coverArtPath,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Headphones,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Title + author
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = author,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Play/Pause indicator
            Icon(
                imageVector = if (isPlaying) Icons.Default.Headphones else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Playing" else "Resume",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
