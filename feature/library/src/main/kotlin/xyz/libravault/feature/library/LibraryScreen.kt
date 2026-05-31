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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.core.domain.model.VaultFolder
import xyz.libravault.core.domain.model.BookmarkWithItemInfo
import xyz.libravault.feature.player.service.PlaybackStateHolder
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onItemClick: (LibraryItem) -> Unit,
    onSettingsClick: () -> Unit,
    onNowPlayingClick: (Long) -> Unit,
    onBookmarkItemClick: (LibraryItem, Long) -> Unit = { item, _ -> onItemClick(item) },
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val allBookmarks by viewModel.allBookmarks.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    var isSearchOpen by remember { mutableStateOf(false) }
    var showAllBookmarks by remember { mutableStateOf(false) }
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
            showAddVaultSheet = false  // Close sheet after adding
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
                            IconButton(onClick = { showAddVaultSheet = true }) {
                                Icon(Icons.Default.Add, contentDescription = "Add vault")
                            }
                        }
                        IconButton(onClick = { showAllBookmarks = true }) {
                            Icon(
                                imageVector = Icons.Default.Bookmark,
                                contentDescription = "Bookmarks",
                            )
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
                    title        = nowPlaying.title,
                    author       = nowPlaying.author,
                    coverArtPath = nowPlaying.coverArtPath,
                    isPlaying    = nowPlaying.isPlaying,
                    onArtClick   = { nowPlaying.itemId?.let(onNowPlayingClick) },
                    onPrevious   = viewModel::skipPrevious,
                    onSeekBack   = viewModel::seekBack,
                    onPlayPause  = viewModel::playPause,
                    onSeekForward= viewModel::seekForward,
                    onNext       = viewModel::skipNext,
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
                        }
                    }

                    // ── Format filter chips (always visible outside search) ────
                    if (state.searchResults == null) {
                        item {
                            FormatFilterRow(
                                currentFilter   = state.formatFilter,
                                onFilterChanged = viewModel::onFormatFilterChanged,
                            )
                            Spacer(Modifier.height(4.dp))
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
                        // Single vault view — vaultGroupedItems is already format-filtered by ViewModel
                        val selected = state.selectedVault ?: return@LazyColumn
                        val vaultItems = viewModel.vaultFilteredItems(
                            state.vaultGroupedItems,
                            selected.id,
                        )
                        item { SectionHeader("All items") }
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
                    } else if (state.formatFilter == null) {
                        // All formats: separate Books and Audio sections so neither is hidden
                        val allBooks = state.allItems.filter { !it.format.isAudio() }
                        val allAudio = state.allItems.filter { it.format.isAudio() }
                        if (allBooks.isNotEmpty()) {
                            item(key = "section_books_header") { SectionHeader("Books") }
                            item(key = "section_books_row") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    items(allBooks, key = { it.id }) { item ->
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
                            item(key = "section_books_spacer") { Spacer(Modifier.height(8.dp)) }
                        }
                        if (allAudio.isNotEmpty()) {
                            item(key = "section_audio_header") { SectionHeader("Audio") }
                            item(key = "section_audio_row") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    items(allAudio, key = { it.id }) { item ->
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
                            item(key = "section_audio_spacer") { Spacer(Modifier.height(8.dp)) }
                        }
                    } else {
                        // Format filter active: per-vault rows (already filtered by ViewModel)
                        state.vaultGroupedItems.forEach { (vault, vaultItems) ->
                            if (vaultItems.isEmpty()) return@forEach
                            item(key = "${vault.id}_header") {
                                VaultSectionHeader(
                                    vault = vault,
                                    onClick = { viewModel.selectVault(vault.id) },
                                )
                            }
                            item(key = "${vault.id}_row") {
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
                            item(key = "${vault.id}_spacer") { Spacer(Modifier.height(8.dp)) }
                        }
                    }
                }
            }
        }

    // ── Search overlay ─────────────────────────────────────────────────────
    if (isSearchOpen) {
        @Suppress("DEPRECATION")
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
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                // Format filter chips
                item {
                    FormatFilterRow(
                        currentFilter = state.formatFilter,
                        onFilterChanged = viewModel::onFormatFilterChanged,
                    )
                }
                val filteredResults = state.searchResults?.applyFormatFilter(state.formatFilter)
                if (!filteredResults.isNullOrEmpty()) {
                    items(filteredResults, key = { it.id }) { item ->
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
                } else if (state.searchQuery.isNotBlank()) {
                    item {
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
    }

    // ── Add vault sheet ─────────────────────────────────────────────────────
    if (showAddVaultSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddVaultSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            VaultManagementSheet(
                vaults = state.vaults,
                onAddVault = { launchFolderPicker() },
                onRemoveVault = { vaultToRemove = it },
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    // ── All bookmarks sheet ─────────────────────────────────────────────────
    if (showAllBookmarks) {
        AllBookmarksSheet(
            bookmarks = allBookmarks,
            onBookmarkClick = { bookmark ->
                showAllBookmarks = false
                val item = state.allItems.firstOrNull { it.id == bookmark.bookmark.itemId }
                if (item != null && viewModel.validateItem(item)) {
                    val seekMs = bookmark.bookmark.positionRef.removePrefix("ms:").toLongOrNull()
                    if (seekMs != null && item.format.isAudio()) {
                        onBookmarkItemClick(item, seekMs)
                    } else {
                        onItemClick(item)
                    }
                }
            },
            onDeleteBookmark = viewModel::onDeleteBookmark,
            onDismiss = { showAllBookmarks = false },
        )
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
                    Icon(Icons.Default.Folder, contentDescription = "Folder", modifier = Modifier.size(16.dp))
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
                        )) Icons.Default.Headphones else Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = if (item.format.isAudio()) "Audiobook" else "Book",
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
                        ) Icons.Default.Headphones else Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = if (item.format.isAudio()) "Audiobook" else "Book",
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

// ── Format filter helper ──────────────────────────────────────────────────────

private fun List<LibraryItem>.applyFormatFilter(filter: String?): List<LibraryItem> = when (filter) {
    null   -> this
    "AUDIO" -> filter { it.format.isAudio() }
    else   -> filter { it.format.name == filter }
}

// ── Format filter chips for search ────────────────────────────────────────────

@Composable
private fun FormatFilterRow(
    currentFilter: String?,
    onFilterChanged: (String?) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = currentFilter == null,
                onClick = { onFilterChanged(null) },
                label = { Text("All") },
            )
        }
        item {
            FilterChip(
                selected = currentFilter == MediaFormat.EPUB.name,
                onClick = { onFilterChanged(MediaFormat.EPUB.name) },
                label = { Text("EPUB") },
            )
        }
        item {
            FilterChip(
                selected = currentFilter == MediaFormat.PDF.name,
                onClick = { onFilterChanged(MediaFormat.PDF.name) },
                label = { Text("PDF") },
            )
        }
        item {
            FilterChip(
                selected = currentFilter == "AUDIO",
                onClick = { onFilterChanged("AUDIO") },
                label = { Text("Audio") },
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
            ) Icons.Default.Headphones else Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = if (item.format.isAudio()) "Audiobook" else "Book",
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

// ── All bookmarks sheet ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllBookmarksSheet(
    bookmarks: List<BookmarkWithItemInfo>,
    onBookmarkClick: (BookmarkWithItemInfo) -> Unit,
    onDeleteBookmark: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Bookmarks",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            if (bookmarks.isEmpty()) {
                Text(
                    text = "No bookmarks yet.\nAdd bookmarks from the player or reader.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                    items(bookmarks, key = { it.bookmark.id }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onBookmarkClick(entry) }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bookmark,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.bookmark.label ?: "Bookmark",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${entry.itemTitle} — ${entry.itemAuthor}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { onDeleteBookmark(entry.bookmark.id) }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete bookmark",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 24.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                        )
                    }
                }
            }
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
    onArtClick: () -> Unit,
    onPrevious: () -> Unit,
    onSeekBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekForward: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Cover art — tapping navigates to the full player
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(onClick = onArtClick),
                contentAlignment = Alignment.Center,
            ) {
                if (coverArtPath != null) {
                    AsyncImage(
                        model = coverArtPath,
                        contentDescription = title,
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

            Spacer(Modifier.width(10.dp))

            // Title + author — tapping also navigates to the full player
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onArtClick),
            ) {
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

            // Playback controls
            IconButton(onClick = onPrevious, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onSeekBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.FastRewind, contentDescription = "Skip back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
            }
            IconButton(onClick = onSeekForward, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.FastForward, contentDescription = "Skip forward",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onNext, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
}
