package xyz.libravault.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.VaultFolder
import xyz.libravault.core.domain.scanner.ScanProgress
import android.net.Uri
import xyz.libravault.core.domain.usecase.AddVaultFolderUseCase
import xyz.libravault.core.domain.usecase.GetLibraryUseCase
import xyz.libravault.core.storage.VaultManager
import xyz.libravault.core.domain.usecase.ObserveCurrentlyReadingUseCase
import xyz.libravault.core.domain.usecase.ObserveVaultsUseCase
import xyz.libravault.core.domain.usecase.ScanVaultUseCase
import xyz.libravault.core.domain.usecase.SearchLibraryUseCase
import xyz.libravault.core.logger.LibravaultLogger
import xyz.libravault.feature.player.service.PlaybackStateHolder
import javax.inject.Inject

private data class ControlState(
    val scanning: Boolean = false,
    val error: String? = null,
    val query: String = "",
    val results: List<LibraryItem>? = null,
    val stale: Boolean = false,
    val selectedId: Long? = null,
)

data class LibraryUiState(
    val vaults: List<VaultFolder>         = emptyList(),
    val currentBook: LibraryItem?         = null,
    val currentAudiobook: LibraryItem?    = null,
    val allItems: List<LibraryItem>       = emptyList(),
    val searchResults: List<LibraryItem>? = null,   // null = not in search mode
    val searchQuery: String               = "",
    val selectedVaultId: Long?            = null,   // null = showing all vaults
    val groupedItems: Map<Long, List<LibraryItem>> = emptyMap(),
    val isScanning: Boolean               = false,
    val scanError: String?                = null,
    val staleItemMessage: Boolean         = false,  // "File not found" snackbar
    val addVaultError: String?            = null,
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
    private val vaultManager: VaultManager,
    private val logger: LibravaultLogger,
    private val playbackStateHolder: PlaybackStateHolder,
) : ViewModel() {

    private val _scanning      = MutableStateFlow(false)
    private val _addVaultError = MutableStateFlow<String?>(null)
    private val _scanError     = MutableStateFlow<String?>(null)
    private val _selectedVaultId = MutableStateFlow<Long?>(null)
    private val _searchQuery     = MutableStateFlow("")
    private val _searchResults = MutableStateFlow<List<LibraryItem>?>(null)
    private val _staleMessage  = MutableStateFlow(false)
    private var searchJob: Job? = null

    val nowPlaying: StateFlow<PlaybackStateHolder.State> = playbackStateHolder.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaybackStateHolder.State())

    val uiState: StateFlow<LibraryUiState> = combine(
        observeVaults(),
        getLibrary(),
        observeCurrentlyReading.book(),
        observeCurrentlyReading.audiobook(),
        combine(listOf(_scanning, _scanError, _searchQuery, _searchResults, _staleMessage, _selectedVaultId)) { arr ->
            ControlState(
                scanning = arr[0] as Boolean,
                error = arr[1] as? String,
                query = arr[2] as String,
                results = @Suppress("UNCHECKED_CAST") arr[3] as? List<LibraryItem>,
                stale = arr[4] as Boolean,
                selectedId = arr[5] as Long?,
            )
        },
    ) { vaults, items, book, audiobook, control ->
        val grouped = items.groupBy { it.vaultFolderId }

        LibraryUiState(
            vaults           = vaults,
            allItems         = items,
            currentBook      = book,
            currentAudiobook = audiobook,
            isScanning       = control.scanning,
            scanError        = control.error,
            searchQuery      = control.query,
            searchResults    = control.results,
            selectedVaultId  = control.selectedId,
            groupedItems     = grouped,
            staleItemMessage = control.stale,
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

    // ── Scanning ─────────────────────────────────────────────────────────────

    /** Triggered on cold start and on vault addition. */
    fun triggerScan() {
        if (_scanning.value) return
        viewModelScope.launch {
            _scanning.value  = true
            _scanError.value = null

            scanVault().collect { progress ->
                when (progress) {
                    is ScanProgress.Error -> {
                        _scanError.value = progress.message
                        logger.e("LibraryVM", "Scan error: ${progress.message}")
                    }
                    is ScanProgress.Completed ->
                        logger.i("LibraryVM", "Scan complete: ${progress.total} items")
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

    // ── Stale file handling ───────────────────────────────────────────────────

    /** Returns false if the file is inaccessible — shows snackbar to user. */
    fun validateItem(item: LibraryItem): Boolean {
        // Full SAF URI validation happens in M2/M3 when the reader opens the file.
        // For now we optimistically trust the scan result.
        return true
    }

    fun showStaleMessage() { _staleMessage.value = true }
    fun dismissStaleMessage() { _staleMessage.value = false }

    // ── Init ─────────────────────────────────────────────────────────────────

    init {
        viewModelScope.launch {
            // Recovery: if Room has no vaults but the OS still holds URI permissions
            // (e.g. after user clears app cache), re-register the persisted URIs so
            // the scanner can find the files again.
            val vaults = observeVaults().first()
            if (vaults.isEmpty()) {
                val persistedUris = vaultManager.persistedVaultUris()
                if (persistedUris.isNotEmpty()) {
                    logger.i("LibraryVM", "Room empty but ${persistedUris.size} URI permission(s) found — recovering vaults")
                    persistedUris.forEach { uri ->
                        runCatching {
                            addVaultFolder(
                                uri.toString(),
                                uri.lastPathSegment?.substringAfterLast(':')
                                    ?.substringAfterLast('/') ?: "My Vault"
                            )
                        }
                    }
                }
            }
        }
        triggerScan()
    }
}
