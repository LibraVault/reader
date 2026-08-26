package xyz.libravault.feature.library

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.VaultFolder
import xyz.libravault.core.ui.theme.Dimens
import xyz.libravault.feature.player.service.PlaybackStateHolder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onItemClick: (LibraryItem) -> Unit,
    onSettingsClick: () -> Unit,
    // #493 — the full holder state, not just an itemId, so the caller can route to
    // either Screen.Player (real file) or Screen.VaultPlay (vaultEntry != null).
    onNowPlayingClick: (PlaybackStateHolder.State) -> Unit,
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
            if (shouldShowMiniPlayer(nowPlaying)) {
                MiniPlayerBar(
                    title        = nowPlaying.title,
                    author       = nowPlaying.author,
                    coverArtPath = nowPlaying.coverArtPath,
                    isPlaying    = nowPlaying.isPlaying,
                    onArtClick   = { onNowPlayingClick(nowPlaying) },
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
        if (event == Lifecycle.Event.ON_RESUME) {
            android.util.Log.i("DIAG-SettingsStall", "ON_RESUME fired @ ${System.currentTimeMillis()}")
            onResume()
            android.util.Log.i("DIAG-SettingsStall", "onResume() callback returned @ ${System.currentTimeMillis()}")
        }
    }
