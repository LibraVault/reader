package xyz.libravault.feature.library

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.core.domain.model.VaultFolder
import xyz.libravault.core.domain.model.BookmarkWithItemInfo
import xyz.libravault.core.domain.scanner.ScanProgress
import android.net.Uri
import xyz.libravault.core.domain.usecase.AddVaultFolderUseCase
import xyz.libravault.core.domain.usecase.GetLibraryUseCase
import xyz.libravault.core.domain.usecase.RemoveVaultFolderUseCase
import xyz.libravault.core.storage.SupporterRepository
import xyz.libravault.core.storage.VaultManager
import xyz.libravault.core.domain.usecase.ObserveCurrentlyReadingUseCase
import xyz.libravault.core.domain.usecase.ObserveVaultsUseCase
import xyz.libravault.core.domain.usecase.ScanVaultUseCase
import xyz.libravault.core.domain.usecase.SearchLibraryUseCase
import xyz.libravault.core.domain.usecase.ObserveAllBookmarksUseCase
import xyz.libravault.core.domain.usecase.DeleteBookmarkUseCase
import xyz.libravault.core.logger.LibravaultLogger
import com.google.common.util.concurrent.MoreExecutors
import xyz.libravault.feature.player.service.PlaybackStateHolder
import xyz.libravault.feature.player.service.SkipDurationPreference
import javax.inject.Inject

data class LibraryUiState(
    val vaults: List<VaultFolder>         = emptyList(),
    val continueItems: List<LibraryItem>  = emptyList(),  // Most recent reading + listening, interleaved.
    val allItems: List<LibraryItem>       = emptyList(),
    val searchResults: List<LibraryItem>? = null,   // null = not in search mode
    val searchQuery: String               = "",
    val formatFilter: String?             = null,    // null = all formats, else MediaFormat.name
    val isScanning: Boolean               = false,
    val scanError: String?                = null,
    val staleItemMessage: Boolean         = false,  // "File not found" snackbar
    val addVaultError: String?            = null,
    // Vault browsing
    val vaultGroupedItems: Map<VaultFolder, List<LibraryItem>> = emptyMap(),
    val selectedVault: VaultFolder?       = null,
    // Mini-player
    val nowPlaying: PlaybackStateHolder.State = PlaybackStateHolder.State(),
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    observeVaults: ObserveVaultsUseCase,
    getLibrary: GetLibraryUseCase,
    observeCurrentlyReading: ObserveCurrentlyReadingUseCase,
    private val scanVault: ScanVaultUseCase,
    private val searchLibrary: SearchLibraryUseCase,
    private val addVaultFolder: AddVaultFolderUseCase,
    private val removeVaultFolder: RemoveVaultFolderUseCase,
    private val vaultManager: VaultManager,
    private val logger: LibravaultLogger,
    private val playbackStateHolder: PlaybackStateHolder,
    private val observeAllBookmarks: ObserveAllBookmarksUseCase,
    private val deleteBookmark: DeleteBookmarkUseCase,
    private val controllerFuture: ListenableFuture<MediaController>,
    private val supporterRepository: SupporterRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private var controller: MediaController? = null

    private val _scanning      = MutableStateFlow(false)
    private val _addVaultError = MutableStateFlow<String?>(null)
    private val _scanError     = MutableStateFlow<String?>(null)
    private val _selectedVaultId = MutableStateFlow<Long?>(null)
    private val _searchQuery     = MutableStateFlow("")
    private val _searchResults = MutableStateFlow<List<LibraryItem>?>(null)
    private val _staleMessage  = MutableStateFlow(false)
    private val _selectedVaultFilter = MutableStateFlow<Long?>(null)
    private val _formatFilter = MutableStateFlow<String?>(null)
    private var searchJob: Job? = null
    private var scanJob: Job? = null

    val nowPlaying: StateFlow<PlaybackStateHolder.State> = playbackStateHolder.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaybackStateHolder.State())

    val isSupporter: StateFlow<Boolean> = supporterRepository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), supporterRepository.isSupporter())

    val allBookmarks: StateFlow<List<BookmarkWithItemInfo>> = observeAllBookmarks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _extras = combine(
        _scanning, _scanError, _searchQuery, _searchResults, _staleMessage, _selectedVaultFilter, _formatFilter,
    ) { arr: Array<*> ->
        Extras(
            scanning = arr[0] as Boolean,
            error = arr[1] as? String,
            query = arr[2] as String,
            results = @Suppress("UNCHECKED_CAST") arr[3] as? List<LibraryItem>,
            stale = arr[4] as Boolean,
            vaultId = arr[5] as Long?,
            format = arr[6] as? String,
        )
    }

    private data class Extras(
        val scanning: Boolean,
        val error: String?,
        val query: String,
        val results: List<LibraryItem>?,
        val stale: Boolean,
        val vaultId: Long?,
        val format: String?,
    )

    val uiState: StateFlow<LibraryUiState> = combine(
        observeVaults(),
        getLibrary(),
        observeCurrentlyReading.reading(),
        observeCurrentlyReading.listening(),
        _extras,
    ) { vaults, items, reading, listening, extras ->
        val vaultById = vaults.associateBy { it.id }

        // Apply format filter at the ViewModel level so the UI always receives
        // pre-filtered state and never needs to call applyFormatFilter itself.
        val filteredItems: List<LibraryItem> = when (val fmt = extras.format) {
            null   -> items
            "AUDIO" -> items.filter { it.format.isAudio() }
            "BOOK"  -> items.filter { !it.format.isAudio() }
            else   -> items.filter { it.format.name == fmt }
        }

        val grouped = filteredItems.groupBy { item ->
            vaultById[item.vaultFolderId] ?: VaultFolder(
                id = item.vaultFolderId,
                uri = "",
                displayName = "Unknown",
            )
        }

        // Interleave reading + listening so the most-recently-active item is first
        // regardless of format. Round-robin merge by last-activity time.
        val continueItems = interleaveByRecency(reading, listening)

        LibraryUiState(
            vaults            = vaults,
            allItems          = items,          // intentionally unfiltered (used for bookmark lookup)
            continueItems     = continueItems,
            isScanning        = extras.scanning,
            scanError         = extras.error,
            searchQuery       = extras.query,
            searchResults     = extras.results,
            staleItemMessage  = extras.stale,
            formatFilter      = extras.format,
            vaultGroupedItems = grouped,
            selectedVault     = extras.vaultId?.let { vaultById[it] },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState(),
    )

    // ── Vault management ─────────────────────────────────────────────────────

    fun onVaultPicked(uri: Uri, displayName: String) {
        viewModelScope.launch {
            runCatching {
                vaultManager.persistPermission(uri)
                addVaultFolder(uri.toString(), displayName)
                logger.i("LibraryVM", "Vault added: $displayName — triggering scan")
                triggerScan()
            }.onFailure { e ->
                logger.e("LibraryVM", "Failed to add vault", e)
                _addVaultError.value = e.message
            }
        }
    }

    fun dismissVaultError() { _addVaultError.value = null }

    fun removeVault(vault: VaultFolder) {
        viewModelScope.launch {
            val uri = Uri.parse(vault.uri)
            vaultManager.releasePermission(uri)
            removeVaultFolder(vault.id)
            logger.i("LibraryVM", "Vault removed: ${vault.displayName}")
            // Clear filter if the removed vault was selected
            if (_selectedVaultFilter.value == vault.id) {
                _selectedVaultFilter.value = null
            }
        }
    }

    // ── Scanning ─────────────────────────────────────────────────────────────

    /** Triggered on cold start and on vault addition. */
    fun triggerScan() {
        // Capture the old job before overwriting scanJob. cancelAndJoin() waits
        // for the old flow's finally-block to run, which releases the AtomicBoolean
        // lock inside LibraryScannerImpl. Starting the new scan only after that
        // guarantees the scanner isn't skipped due to a stale "in progress" flag.
        val previousJob = scanJob
        scanJob = viewModelScope.launch {
            previousJob?.cancelAndJoin()
            _scanning.value  = true
            _scanError.value = null

            scanVault().collect { progress ->
                when (progress) {
                    is ScanProgress.Error -> {
                        _scanError.value = progress.message
                        logger.e("LibraryVM", "Scan error: ${progress.message}")
                    }
                    is ScanProgress.Completed -> {
                        val fc = progress.formatCounts
                        val breakdown = if (fc != null) {
                            " (${fc.epub} EPUB, ${fc.pdf} PDF, ${fc.audiobook} audiobooks)"
                        } else ""
                        logger.i("LibraryVM", "Scan complete: ${progress.total} items$breakdown")
                    }
                    else -> Unit
                }
            }
            _scanning.value = false
        }
    }

    /** Pull-to-refresh gesture. */
    fun refresh() = triggerScan()

    // ── Search ────────────────────────────────────────────────────────────────

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) { _searchResults.value = null; return }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300) // Debounce: wait 300ms after last keystroke
            _searchResults.value = searchLibrary(query)
        }
    }

    fun clearSearch() {
        _searchQuery.value   = ""
        _searchResults.value = null
    }

    // ── Vault filter (folder-based browsing) ──────────────────────────────────

    fun selectVault(vaultId: Long) {
        _selectedVaultFilter.value = vaultId
    }

    fun clearVaultFilter() {
        _selectedVaultFilter.value = null
    }

    /** Returns filtered items based on selected vault, or all items for the "All" view. */
    fun vaultFilteredItems(vaultGrouped: Map<VaultFolder, List<LibraryItem>>, selectedVaultId: Long?): List<LibraryItem> {
        if (selectedVaultId == null) return emptyList() // handled at UI level
        return vaultGrouped.entries.firstOrNull { it.key.id == selectedVaultId }?.value ?: emptyList()
    }

    // ── Format filter ──────────────────────────────────────────────────────────

    fun onFormatFilterChanged(format: String?) {
        _formatFilter.value = format
    }

    fun clearFormatFilter() {
        _formatFilter.value = null
    }

    /** Returns filtered items based on format, or all items if no format filter is set. */
    fun formatFilteredItems(items: List<LibraryItem>, formatFilter: String?): List<LibraryItem> {
        return when (formatFilter) {
            null    -> items
            "AUDIO" -> items.filter { it.format.isAudio() }
            "BOOK"  -> items.filter { !it.format.isAudio() }
            else    -> items.filter { it.format.name == formatFilter }
        }
    }

    // ── Stale file handling ───────────────────────────────────────────────────

    /** Returns false if the file is inaccessible — shows snackbar to user. */
    fun validateItem(item: LibraryItem): Boolean {
        // Full SAF URI validation happens in M2/M3 when the reader opens the file.
        // For now we optimistically trust the scan result.
        return true
    }

    fun showStaleMessage() { _staleMessage.value = true }
    fun dismissStaleMessage() { _staleMessage.value = false }

    // ── Bookmark management ──────────────────────────────────────────────────

    fun onDeleteBookmark(bookmarkId: Long) {
        viewModelScope.launch {
            deleteBookmark(bookmarkId)
            logger.i("LibraryVM", "Bookmark deleted: $bookmarkId")
        }
    }

    // ── Mini-player playback controls ────────────────────────────────────────

    fun playPause() {
        val ctrl = controller ?: return
        val wasPlaying = ctrl.isPlaying
        if (wasPlaying) ctrl.pause() else ctrl.play()
        // Update PlaybackStateHolder immediately so the mini-player icon flips
        // without waiting for PlayerViewModel's 200ms polling tick.
        val current = playbackStateHolder.state.value
        if (current.itemId != null) {
            playbackStateHolder.update(
                itemId       = current.itemId,
                title        = current.title,
                author       = current.author,
                coverArtPath = current.coverArtPath,
                isPlaying    = !wasPlaying,
            )
        }
    }

    fun seekBack()    { seekBy(-SkipDurationPreference.getSkipDurationMs(appContext)) }
    fun seekForward() { seekBy( SkipDurationPreference.getSkipDurationMs(appContext)) }
    fun skipPrevious() { controller?.seekToPreviousMediaItem() }
    fun skipNext()     { controller?.seekToNextMediaItem() }

    /**
     * Seeks [deltaMs] from the current position, clamped to the item duration.
     * Uses an explicit `seekTo` rather than `MediaController.seekBack` /
     * `MediaController.seekForward` so the user's runtime `defaultSkipDurationSec`
     * preference is honored even after the ExoPlayer singleton has been built (the
     * `seekBackIncrementMs` / `seekForwardIncrementMs` values on `ExoPlayer.Builder`
     * are immutable past build).
     */
    private fun seekBy(deltaMs: Long) {
        val ctrl = controller ?: return
        val target = (ctrl.currentPosition + deltaMs)
            .coerceIn(0L, ctrl.duration.coerceAtLeast(0L))
        ctrl.seekTo(target)
    }

    // ── Init ─────────────────────────────────────────────────────────────────

    init {
        // Resolve MediaController for mini-player controls (non-blocking)
        controllerFuture.addListener(
            {
                runCatching { controller = controllerFuture.get() }
                    .onFailure { logger.w("LibraryVM", "MediaController not available for mini-player: ${it.message}") }
            },
            MoreExecutors.directExecutor(),
        )
        viewModelScope.launch {
            // Recovery: if Room has no vaults but the OS still holds URI permissions
            // (e.g. after user clears app cache), re-register the persisted URIs so
            // the scanner can find the files again.
            val initialVaults = observeVaults().first()
            if (initialVaults.isEmpty()) {
                val persistedUris = vaultManager.persistedVaultUris()
                if (persistedUris.isNotEmpty()) {
                    logger.i("LibraryVM", "Room empty but ${persistedUris.size} URI permission(s) found — recovering vaults")
                    var recoveredCount = 0
                    persistedUris.forEach { uri ->
                        runCatching {
                            addVaultFolder(
                                uri.toString(),
                                uri.lastPathSegment?.substringAfterLast(':')
                                    ?.substringAfterLast('/') ?: "My Vault"
                            )
                            recoveredCount++
                        }.onFailure { e ->
                            logger.w("LibraryVM", "Failed to recover vault URI: $uri", e)
                        }
                    }
                    logger.i("LibraryVM", "Recovery completed: $recoveredCount vault(s) inserted")

                    // Wait briefly for Room to propagate the new vaults,
                    // but don’t block indefinitely — if we timeout, scan anyway.
                    try {
                        withTimeout(2000) {
                            observeVaults().first { it.isNotEmpty() }
                        }
                        logger.i("LibraryVM", "Vaults visible — proceeding to scan")
                    } catch (_: TimeoutCancellationException) {
                        logger.w("LibraryVM", "Timeout waiting for vaults — proceeding to scan anyway")
                    }
                }
            }
            // Scan after recovery so the scanner always sees existing vaults.
            // This avoids a race where triggerScan() runs concurrently with
            // the recovery coroutine and finds 0 vaults.
            triggerScan()
        }
    }
}

/**
 * Merge two already-sorted "in progress" lists into one ordered list.
 * Each source list is already sorted by last-activity (most recent first);
 * the result interleaves them so the freshest item from either list comes
 * first. Duplicates (same item appearing in both lists) are removed.
 */
private fun interleaveByRecency(
    reading: List<LibraryItem>,
    listening: List<LibraryItem>,
): List<LibraryItem> {
    val seen = HashSet<Long>()
    val out = ArrayList<LibraryItem>(reading.size + listening.size)
    var i = 0
    var j = 0
    while (i < reading.size || j < listening.size) {
        val takeReading = when {
            i >= reading.size -> false
            j >= listening.size -> true
            else -> true  // Both have items — alternate to interleave
        }
        if (takeReading) {
            val item = reading[i++]
            if (seen.add(item.id)) out += item
        } else {
            val item = listening[j++]
            if (seen.add(item.id)) out += item
        }
    }
    return out
}
