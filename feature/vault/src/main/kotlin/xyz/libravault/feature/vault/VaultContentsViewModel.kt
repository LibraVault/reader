package xyz.libravault.feature.vault

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.libravault.core.storage.CoverArtCache
import xyz.libravault.core.storage.MetadataExtractor
import xyz.libravault.core.storage.model.ScannedFile
import xyz.libravault.core.vaultstore.VaultManifestEntry
import xyz.libravault.core.vaultstore.VaultSessionManager
import xyz.libravault.core.vaultstore.VaultStore
import javax.inject.Inject
import xyz.libravault.core.storage.model.MediaFormat as StorageMediaFormat

enum class ImportItemStatus { PENDING, IMPORTING, DONE, ERROR }

data class ImportItemUiState(
    val uri: Uri,
    val displayName: String,
    val status: ImportItemStatus = ImportItemStatus.PENDING,
    val errorMessage: String? = null,
)

data class VaultContentsUiState(
    val displayName: String = "",
    val entries: List<VaultManifestEntry> = emptyList(),
    val isLoading: Boolean = true,
    /** Flips true if [refresh] finds the vault has been locked out from under
     * this screen (auto-lock firing while it was in front). The screen should
     * pop back to the vault list rather than show a stale entry list. */
    val wasLocked: Boolean = false,
    /** Non-empty while an import run is active or has just finished — cleared
     * by [VaultContentsViewModel.dismissImportSummary]. Per-item status, not
     * just a single spinner, since one bad file in a multi-file pick shouldn't
     * hide whether the others succeeded. */
    val importItems: List<ImportItemUiState> = emptyList(),
    /** Decrypted cover-art JPEG bytes, keyed by [VaultManifestEntry.fileId]'s
     * [toHexString], for entries that have one (issue #169). Populated by
     * [refresh] — never written to disk, decoded to a bitmap only in Compose
     * (see [VaultContentsScreen]), same "plaintext image bytes never touch
     * unencrypted storage" rule as the import path's [CoverArtCache]. Entries
     * with no cover, or whose decrypt failed, simply have no map entry. */
    val coverArt: Map<String, ByteArray> = emptyMap(),
)

/**
 * Vault contents: browse ([refresh]/[lock], Phase 5a) plus import
 * ([onFilesPicked], Phase 5b). Requires the vault named by the `vaultId`
 * nav argument to already be unlocked; if it isn't (or auto-lock fires while
 * this screen is in front), [refresh] surfaces [VaultContentsUiState.wasLocked]
 * rather than crashing on [VaultSessionManager.requireUnlocked].
 *
 * Import deliberately reuses [MetadataExtractor.extractWithoutCaching], never
 * [MetadataExtractor.extract] — the latter's cover-art handling writes a
 * plaintext copy to [CoverArtCache]'s disk cache as a side effect, which
 * would defeat the entire point of encrypting this file. See that method's
 * doc comment.
 */
@HiltViewModel
class VaultContentsViewModel @Inject constructor(
    private val sessionManager: VaultSessionManager,
    private val metadataExtractor: MetadataExtractor,
    private val coverArtCache: CoverArtCache,
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val vaultId: String = checkNotNull(savedStateHandle["vaultId"]) { "VaultContentsScreen requires a vaultId nav argument" }

    private val _uiState = MutableStateFlow(VaultContentsUiState())
    val uiState: StateFlow<VaultContentsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val name = sessionManager.listVaults().find { it.id == vaultId }?.displayName ?: ""
            _uiState.update { it.copy(displayName = name) }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            if (!sessionManager.isUnlocked(vaultId)) {
                _uiState.update { it.copy(isLoading = false, wasLocked = true) }
                return@launch
            }
            val store = sessionManager.requireUnlocked(vaultId)
            val entries = store.listEntries()
            val coverArt = loadCoverThumbnails(store, entries)
            _uiState.update { it.copy(entries = entries, coverArt = coverArt, isLoading = false) }
        }
    }

    /** Decrypts every entry's cover art, one at a time — [VaultStore] isn't
     * safe for concurrent callers (see [importOne]'s sequential loop for the
     * same reason), so this can't `async`/`awaitAll`. A single bad cover
     * (corrupt content, mid-write torn read) is dropped rather than failing
     * the whole list — [entries] still needs to render either way. */
    private suspend fun loadCoverThumbnails(
        store: VaultStore,
        entries: List<VaultManifestEntry>,
    ): Map<String, ByteArray> {
        val covers = mutableMapOf<String, ByteArray>()
        for (entry in entries) {
            if (entry.coverArtFileId == null) continue
            runCatching { store.readCoverArt(entry.fileId) }
                .getOrNull()
                ?.let { covers[entry.fileId.toHexString()] = it }
        }
        return covers
    }

    fun lock() {
        sessionManager.lock(vaultId)
    }

    // ── Import ───────────────────────────────────────────────────────────────

    /** Imports every picked [uris] into this vault, one at a time (not
     * parallel — [VaultStore][xyz.libravault.core.vaultstore.VaultStore]
     * is explicitly not safe for concurrent callers). A file that fails
     * (unsupported type, read error, insufficient storage) is marked
     * [ImportItemStatus.ERROR] and the run continues with the rest, rather
     * than aborting the whole batch. */
    fun onFilesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val items = uris.map { uri -> ImportItemUiState(uri, displayNameFor(uri)) }
        _uiState.update { it.copy(importItems = items) }

        viewModelScope.launch {
            for (item in items) {
                updateImportItem(item.uri) { it.copy(status = ImportItemStatus.IMPORTING) }
                try {
                    importOne(item.uri, item.displayName)
                    updateImportItem(item.uri) { it.copy(status = ImportItemStatus.DONE) }
                } catch (e: Exception) {
                    updateImportItem(item.uri) {
                        it.copy(status = ImportItemStatus.ERROR, errorMessage = e.message ?: e.javaClass.simpleName)
                    }
                }
            }
            refresh()
        }
    }

    /** Clears the import summary overlay — called once the user has seen the
     * per-item results and dismisses it. Does not retry anything; a failed
     * item is simply gone from the list until the user re-picks it. */
    fun dismissImportSummary() {
        _uiState.update { it.copy(importItems = emptyList()) }
    }

    private suspend fun importOne(uri: Uri, displayName: String) {
        val mimeType = context.contentResolver.getType(uri) ?: ""
        val format = StorageMediaFormat.fromMimeOrName(mimeType, displayName)
            ?: error("Unsupported file type")
        val declaredSize = querySize(uri)
        val scannedFile = ScannedFile(uri, displayName, mimeType, format, declaredSize)

        val (metadata, rawCoverBytes) = metadataExtractor.extractWithoutCaching(scannedFile)
        // Downsample here — VaultStore.setCoverArt/importFile deliberately
        // don't decode cover bytes themselves (see their doc comments); this
        // is the one hardened, tested place that does.
        val coverJpeg = rawCoverBytes?.let { coverArtCache.downsampleToJpeg(it, logKey = displayName) }

        val stream = context.contentResolver.openInputStream(uri) ?: error("Could not open $displayName")
        stream.use { input ->
            sessionManager.requireUnlocked(vaultId).importFile(
                input        = input,
                declaredSize = declaredSize,
                title        = metadata.title,
                author       = metadata.author,
                // .name, not a mapping table: core.storage.model.MediaFormat and
                // core.domain.model.MediaFormat (which the vault-native reading
                // screens parse this back into) share identical entry names.
                format       = format.name,
                coverArt     = coverJpeg,
            )
        }
    }

    private fun updateImportItem(uri: Uri, transform: (ImportItemUiState) -> ImportItemUiState) {
        _uiState.update { s -> s.copy(importItems = s.importItems.map { if (it.uri == uri) transform(it) else it }) }
    }

    private fun displayNameFor(uri: Uri): String {
        queryColumn(uri, OpenableColumns.DISPLAY_NAME) { cursor, idx -> cursor.getString(idx) }
            ?.let { return it }
        return uri.lastPathSegment ?: "file"
    }

    private fun querySize(uri: Uri): Long {
        queryColumn(uri, OpenableColumns.SIZE) { cursor, idx -> if (cursor.isNull(idx)) null else cursor.getLong(idx) }
            ?.let { return it }
        // A wrong declared size here isn't a silent corruption risk: it's the
        // exact total ChunkedVaultWriter.encrypt authenticates against, and
        // that throws IllegalStateException on a mismatch with what the
        // stream actually produced — importOne's caller (onFilesPicked)
        // already catches that and marks the item ERROR.
        return 0L
    }

    /** Queries a single [OpenableColumns] value, tolerating a provider that
     * returns no cursor, an empty cursor, or throws outright (a hostile or
     * simply buggy `DocumentsProvider` shouldn't be able to crash an import —
     * only degrade it to the filename/zero-size fallbacks above). */
    private fun <T> queryColumn(uri: Uri, column: String, extract: (android.database.Cursor, Int) -> T?): T? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(column)
                if (idx >= 0 && cursor.moveToFirst()) extract(cursor, idx) else null
            }
        }.getOrNull()
}
