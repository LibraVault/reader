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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.core.domain.model.VaultFolder
import xyz.libravault.core.domain.model.BookmarkWithItemInfo
import xyz.libravault.core.ui.components.GeneratedCover
import xyz.libravault.core.ui.theme.Dimens
import xyz.libravault.feature.library.R
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

    // What the grid shows is decided in one place, in pure code, so it can be
    // tested — see LibraryScreenLogic.kt. This body previously carried the same
    // decision as a chain of interlocking `if`s spread over ~150 lines.
    val layout = remember(state) { libraryLayoutFor(state) }

    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val allBookmarks by viewModel.allBookmarks.collectAsState()
    val isSupporter by viewModel.isSupporter.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    var isSearchOpen by remember { mutableStateOf(false) }
    var showAllBookmarks by remember { mutableStateOf(false) }
    var overflowMenuOpen by remember { mutableStateOf(false) }
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
            viewModel.onVaultPicked(uri, vaultDisplayNameFrom(uri.lastPathSegment))
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

    // Re-scan whenever the screen becomes visible again, not just on cold start
    // or vault addition — e.g. after the user drops new files into a vault
    // folder from a file manager, then switches back to the app (see #96).
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentViewModel = rememberUpdatedState(viewModel)
    DisposableEffect(lifecycleOwner) {
        val observer = libraryResumeObserver { currentViewModel.value.refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
                            ) {
                                BrandMonogram()
                                Text(
                                    text = "LibraVault",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                if (isSupporter) SupporterBadge()
                            }
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
                        IconButton(onClick = { isSearchOpen = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = { showAddVaultSheet = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add vault")
                        }
                        Box {
                            IconButton(onClick = { overflowMenuOpen = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More",
                                )
                            }
                            DropdownMenu(
                                expanded = overflowMenuOpen,
                                onDismissRequest = { overflowMenuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Bookmarks") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Bookmark, contentDescription = null)
                                    },
                                    onClick = {
                                        overflowMenuOpen = false
                                        showAllBookmarks = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Settings, contentDescription = null)
                                    },
                                    onClick = {
                                        overflowMenuOpen = false
                                        onSettingsClick()
                                    },
                                )
                            }
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
            if (layout.content == LibraryContent.EmptyLibrary) {
                EmptyLibrary(
                    hasVaults = state.vaults.isNotEmpty(),
                    onAddVault = { showAddVaultSheet = true },
                    onRescan = viewModel::refresh,
                )
            } else {
                // Single scrolling surface: a vertically-scrollable adaptive grid whose headers,
                // chips, and the horizontally-scrolling "Continue" row are full-width items that
                // span every column. This mirrors the iOS LazyVGrid-in-a-ScrollView layout instead
                // of nesting a horizontally-scrolling row per section.
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = Dimens.coverWidth),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Dimens.spaceLg,
                        end = Dimens.spaceLg,
                        bottom = Dimens.spaceXxl,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
                    verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
                ) {
                    val fullSpan: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) =
                        { GridItemSpan(maxLineSpan) }

                    // ── Continue cards — horizontally scrollable row of fixed-width covers ──────
                    val continueItems = state.continueItems
                    if (layout.showContinue) {
                        item(span = fullSpan) {
                            Column {
                                Text(
                                    text = "Continue",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.padding(vertical = Dimens.spaceSm),
                                )
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
                                ) {
                                    items(continueItems, key = { it.id }) { item ->
                                        ContinueCard(
                                            item    = item,
                                            onClick = {
                                                if (viewModel.validateItem(item)) onItemClick(item)
                                                else viewModel.showStaleMessage()
                                            },
                                            modifier = Modifier.width(Dimens.coverWidth),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(Dimens.spaceLg))
                            }
                        }
                    }

                    // ── Vault filter chips (only when not already filtered) ────
                    if (layout.showVaultChips) {
                        item(span = fullSpan) {
                            VaultFilterChips(
                                vaults = state.vaults,
                                selectedVaultId = state.selectedVault?.id,
                                onSelectVault = viewModel::selectVault,
                                onShowAll = viewModel::clearVaultFilter,
                            )
                        }
                    }

                    // ── Format filter chips (always visible outside search) ────
                    if (layout.showFormatChips) {
                        item(span = fullSpan) {
                            Column {
                                FormatFilterRow(
                                    currentFilter   = state.formatFilter,
                                    onFilterChanged = viewModel::onFormatFilterChanged,
                                    contentPadding  = PaddingValues(vertical = Dimens.spaceXs),
                                )
                                Spacer(Modifier.height(Dimens.spaceXs))
                            }
                        }
                    }

                    // ── Library content ──────────────────────────────────────────
                    if (layout.content == LibraryContent.SearchResults) {
                        // Search results view — same adaptive grid as the rest of the library
                        val items = state.searchResults!!
                        item(span = fullSpan) {
                            SectionHeader("Results for \"${state.searchQuery}\"")
                        }
                        items(items, key = { it.id }) { item ->
                            LibraryItemCard(
                                item = item,
                                onClick = {
                                    if (viewModel.validateItem(item)) onItemClick(item)
                                    else viewModel.showStaleMessage()
                                },
                            )
                        }
                    } else if (layout.content == LibraryContent.SingleVault) {
                        // Single vault view — vaultGroupedItems is already format-filtered by ViewModel
                        val selected = state.selectedVault ?: return@LazyVerticalGrid
                        val vaultItems = viewModel.vaultFilteredItems(
                            state.vaultGroupedItems,
                            selected.id,
                        )
                        item(span = fullSpan) { SectionHeader("All items") }
                        items(vaultItems, key = { it.id }) { item ->
                            LibraryItemCard(
                                item = item,
                                onClick = {
                                    if (viewModel.validateItem(item)) onItemClick(item)
                                    else viewModel.showStaleMessage()
                                },
                            )
                        }
                    } else if (layout.content == LibraryContent.AllGrouped) {
                        // All formats: separate Books and Audio sections so neither is hidden
                        val (allBooks, allAudio) = partitionByMedium(state.allItems)
                        if (allBooks.isNotEmpty()) {
                            item(key = "section_books_header", span = fullSpan) {
                                LibrarySectionHeader(
                                    title = "Reading",
                                    count = allBooks.size,
                                    onViewAll = { viewModel.onFormatFilterChanged("BOOK") },
                                )
                            }
                            items(allBooks, key = { it.id }) { item ->
                                LibraryItemCard(
                                    item = item,
                                    onClick = {
                                        if (viewModel.validateItem(item)) onItemClick(item)
                                        else viewModel.showStaleMessage()
                                    },
                                )
                            }
                            item(key = "section_books_spacer", span = fullSpan) { Spacer(Modifier.height(Dimens.spaceSm)) }
                        }
                        if (allAudio.isNotEmpty()) {
                            item(key = "section_audio_header", span = fullSpan) {
                                LibrarySectionHeader(
                                    title = "Listening",
                                    count = allAudio.size,
                                    onViewAll = { viewModel.onFormatFilterChanged("AUDIO") },
                                )
                            }
                            items(allAudio, key = { it.id }) { item ->
                                LibraryItemCard(
                                    item = item,
                                    onClick = {
                                        if (viewModel.validateItem(item)) onItemClick(item)
                                        else viewModel.showStaleMessage()
                                    },
                                )
                            }
                            item(key = "section_audio_spacer", span = fullSpan) { Spacer(Modifier.height(Dimens.spaceSm)) }
                        }
                    } else if (layout.content == LibraryContent.FilteredEmpty) {
                        // Format filter active, but nothing matches it — most commonly hit by
                        // MD, since most vaults have no Markdown files at all (see #119). The
                        // chips above stay visible and tappable, so this isn't a dead end; it
                        // only explains why the grid below them is blank instead of looking broken.
                        item(span = fullSpan) {
                            FilteredEmptyState(formatFilter = state.formatFilter)
                        }
                    } else {
                        // Format filter active: per-vault sections (already filtered by ViewModel)
                        state.vaultGroupedItems.forEach { (vault, vaultItems) ->
                            if (vaultItems.isEmpty()) return@forEach
                            item(key = "${vault.id}_header", span = fullSpan) {
                                VaultSectionHeader(
                                    vault = vault,
                                    onClick = { viewModel.selectVault(vault.id) },
                                )
                            }
                            items(vaultItems, key = { it.id }) { item ->
                                LibraryItemCard(
                                    item = item,
                                    onClick = {
                                        if (viewModel.validateItem(item)) onItemClick(item)
                                        else viewModel.showStaleMessage()
                                    },
                                )
                            }
                            item(key = "${vault.id}_spacer", span = fullSpan) { Spacer(Modifier.height(Dimens.spaceSm)) }
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
            LazyColumn(contentPadding = PaddingValues(vertical = Dimens.spaceSm)) {
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
                            Modifier.fillMaxWidth().padding(Dimens.spaceXxl),
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
            Spacer(Modifier.height(Dimens.spaceXxl))
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

/**
 * Builds the [LifecycleEventObserver] that drives the "re-scan on return to the
 * screen" behaviour. Extracted as a plain function (rather than inlined into the
 * `DisposableEffect` above) so the ON_RESUME-only filtering can be unit-tested
 * without a Compose host — see [LibraryScreen]'s pull-to-refresh entry point for
 * the manual-trigger counterpart.
 */
internal fun libraryResumeObserver(onResume: () -> Unit): LifecycleEventObserver =
    LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) onResume()
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

// ── Vault browsing components ──────────────────────────────────────────────────

@Composable
private fun VaultFilterChips(
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
private fun VaultSectionHeader(
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

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun SupporterBadge() {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = Color(0xFFFFB300),
    ) {
        Text(
            text = "★ Supporter",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun BrandMonogram() {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.size(28.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "L",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Shown in place of the grid when a format filter chip is active but matches nothing —
 * most commonly MD, since most vaults have no Markdown files at all. Deliberately no CTA
 * button (unlike [EmptyLibrary]): the filter chips stay visible directly above this, so
 * clearing the filter is already one tap away.
 */
@Composable
internal fun FilteredEmptyState(formatFilter: String?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.spaceXl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
            modifier = Modifier.padding(horizontal = Dimens.spaceXl),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
            Text(
                text = stringResource(formatFilterEmptyMessageRes(formatFilter)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Maps a [xyz.libravault.feature.library.LibraryViewModel] format-filter value — a
 * [MediaFormat.name], or the pseudo-formats "AUDIO"/"BOOK" — to the string resource
 * explaining why the grid is empty. Kept as a plain function (not @Composable) so it's
 * unit-testable without a Compose host; the caller resolves it via [stringResource].
 */
internal fun formatFilterEmptyMessageRes(filter: String?): Int = when (filter) {
    MediaFormat.EPUB.name -> R.string.empty_filter_epub
    MediaFormat.PDF.name -> R.string.empty_filter_pdf
    MediaFormat.MARKDOWN.name -> R.string.empty_filter_markdown
    "AUDIO" -> R.string.empty_filter_audio
    "BOOK" -> R.string.empty_filter_book
    else -> R.string.empty_filter_generic
}

@Composable
private fun EmptyLibrary(
    hasVaults: Boolean,
    onAddVault: () -> Unit,
    onRescan: () -> Unit,
) {
    val headlineRes = if (hasVaults) R.string.empty_headline_with_vaults else R.string.empty_headline_no_vaults
    val bodyRes = if (hasVaults) R.string.empty_library_with_vaults else R.string.empty_library_no_vaults
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
                    Text(stringResource(R.string.empty_cta_add_vault))
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

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(vertical = Dimens.spaceSm),
    )
}

@Composable
private fun LibrarySectionHeader(title: String, count: Int, onViewAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Dimens.spaceSm, bottom = Dimens.spaceXs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$title ($count)",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        TextButton(onClick = onViewAll) {
            Text("View all")
        }
    }
}

@Composable
private fun ContinueCard(
    item: LibraryItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(Dimens.spaceSm)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(Dimens.coverAspect)
                    .clip(MaterialTheme.shapes.small),
            ) {
                if (item.coverArtPath != null) {
                    AsyncImage(
                        model = item.coverArtPath,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    GeneratedCover(
                        title = item.title,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // In-progress indicator — thin strip along the cover's bottom edge.
                // Lighter than the full black scrim and signals "resume".
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.secondary),
                )

                // Play chip — small primary-tinted circular surface, top-end.
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Dimens.spaceXs)
                        .size(28.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Resume",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(Dimens.spaceSm))
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.author,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LibraryItemCard(item: LibraryItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(Dimens.spaceSm)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(Dimens.coverAspect)
                    .clip(MaterialTheme.shapes.small),
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
                    GeneratedCover(
                        title = item.title,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.height(Dimens.spaceSm))
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.author,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Format filter helper ──────────────────────────────────────────────────────

private fun List<LibraryItem>.applyFormatFilter(filter: String?): List<LibraryItem> = when (filter) {
    null   -> this
    "AUDIO" -> filter { it.format.isAudio() }
    "BOOK"  -> filter { !it.format.isAudio() }
    else   -> filter { it.format.name == filter }
}

// ── Format filter chips for search ────────────────────────────────────────────

@Composable
internal fun FormatFilterRow(
    currentFilter: String?,
    onFilterChanged: (String?) -> Unit,
    contentPadding: PaddingValues = PaddingValues(horizontal = Dimens.spaceLg, vertical = Dimens.spaceXs),
) {
    LazyRow(
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
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
                selected = currentFilter == MediaFormat.MARKDOWN.name,
                onClick = { onFilterChanged(MediaFormat.MARKDOWN.name) },
                label = { Text("MD") },
            )
        }
        item {
            FilterChip(
                selected = currentFilter == "AUDIO",
                onClick = { onFilterChanged("AUDIO") },
                label = { Text("Listening") },
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
            .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
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
                modifier = Modifier.padding(horizontal = Dimens.spaceXl, vertical = Dimens.spaceLg),
            )
            if (bookmarks.isEmpty()) {
                Text(
                    text = "No bookmarks yet.\nAdd bookmarks from the player or reader.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Dimens.spaceXl),
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = Dimens.spaceXxl)) {
                    items(bookmarks, key = { it.bookmark.id }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onBookmarkClick(entry) }
                                .padding(horizontal = Dimens.spaceXl, vertical = Dimens.spaceMd),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
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
                            modifier = Modifier.padding(horizontal = Dimens.spaceXl),
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
                    .clip(MaterialTheme.shapes.small)
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
                    GeneratedCover(
                        title = title,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Spacer(Modifier.width(Dimens.spaceMd))

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
