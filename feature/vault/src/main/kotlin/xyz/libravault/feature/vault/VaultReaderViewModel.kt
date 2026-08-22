package xyz.libravault.feature.vault

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Publication
import xyz.libravault.core.ui.theme.ReadingTheme
import xyz.libravault.core.vaultcrypto.VaultFileReader
import xyz.libravault.core.vaultstore.VaultBookmark
import xyz.libravault.core.vaultstore.VaultHighlight
import xyz.libravault.core.vaultstore.VaultSessionManager
import xyz.libravault.core.vaultstore.VaultStore
import javax.inject.Inject

sealed class VaultReaderState {
    object Loading : VaultReaderState()
    data class Error(val message: String) : VaultReaderState()
    /** Publication is DRM-restricted (e.g. Adobe ADEPT, LCP) — Libravault has no
     * decryption support, so it's surfaced distinctly from [Error] to show
     * DRM-specific copy instead of a raw parser error message. */
    data class DrmProtected(val schemeName: String?) : VaultReaderState()
    data class EpubReady(val title: String, val publication: Publication) : VaultReaderState()
    /** [reader] stays open for as long as this state is current — the PDF
     * screen reads from it directly (see [VaultReaderViewModel.pdfReader]),
     * unlike EPUB where Readium owns the read lifecycle once [Publication]
     * exists. */
    data class PdfReady(val title: String) : VaultReaderState()
    /** Not a reading format — the caller should have routed audio to
     * [VaultPlayerScreen] instead; surfaced here only as a defensive
     * fallback if that dispatch is ever wrong. */
    data class WrongScreen(val message: String) : VaultReaderState()
}

/**
 * Opens one vault file for reading — EPUB via [VaultReadiumProvider], PDF via
 * the [VaultFileReader] directly (the PDF screen builds its own
 * `ParcelFileDescriptor` from it via `core:vaultcontent`'s proxy-fd/memfd
 * adapters). Reading progress is still not persisted (no progress field on
 * [xyz.libravault.core.vaultstore.VaultManifestEntry] yet) but bookmarks and
 * (EPUB-only, matching `feature:reader`'s own PDF gap) highlights are —
 * stored via [VaultStore.addBookmark]/[VaultStore.addHighlight] in the
 * encrypted manifest, same as the rest of a vault entry's metadata.
 *
 * [settings] ([VaultReaderSettings]) is per-user reading preferences —
 * theme/font size/font family/line spacing — same session-only behavior as
 * `feature:reader`'s `ReaderSettings`: nothing here is persisted, it resets
 * to defaults every time the reader screen opens.
 */
@HiltViewModel
class VaultReaderViewModel @Inject constructor(
    private val sessionManager: VaultSessionManager,
    private val readiumProvider: VaultReadiumProvider,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val vaultId: String = checkNotNull(savedStateHandle["vaultId"]) { "VaultReaderScreen requires a vaultId nav argument" }
    private val fileIdHex: String = checkNotNull(savedStateHandle["fileId"]) { "VaultReaderScreen requires a fileId nav argument" }
    private val fileId: ByteArray = fileIdHex.hexToFileId()

    private val _state = MutableStateFlow<VaultReaderState>(VaultReaderState.Loading)
    val state: StateFlow<VaultReaderState> = _state.asStateFlow()

    private val _settings = MutableStateFlow(VaultReaderSettings())
    val settings: StateFlow<VaultReaderSettings> = _settings.asStateFlow()

    private val _bookmarks = MutableStateFlow<List<VaultBookmark>>(emptyList())
    val bookmarks: StateFlow<List<VaultBookmark>> = _bookmarks.asStateFlow()

    private val _highlights = MutableStateFlow<List<VaultHighlight>>(emptyList())
    val highlights: StateFlow<List<VaultHighlight>> = _highlights.asStateFlow()

    /** The reader's last-reported position — CFI/Locator JSON for EPUB,
     * `"page:N"` for PDF, same convention [VaultBookmark.positionRef] and
     * `core.domain.model.Bookmark.positionRef` both already use. Fed by
     * [onEpubPositionChanged]/[onPdfPageChanged]; [addBookmark] reads it. */
    private val _currentPositionRef = MutableStateFlow<String?>(null)
    val currentPositionRef: StateFlow<String?> = _currentPositionRef.asStateFlow()

    /** Set by [navigateToBookmark] when the user taps a bookmark in the
     * sheet; the screen forwards it to whichever renderer is active and
     * clears it via [clearPendingNavigation] once consumed. */
    private val _pendingNavigationRef = MutableStateFlow<String?>(null)
    val pendingNavigationRef: StateFlow<String?> = _pendingNavigationRef.asStateFlow()

    private var reader: VaultFileReader? = null
    private var publication: Publication? = null
    private var store: VaultStore? = null

    /** Only valid once [state] is [VaultReaderState.PdfReady]. */
    fun pdfReader(): VaultFileReader = requireNotNull(reader) { "PDF reader requested before it was opened" }

    init {
        viewModelScope.launch {
            if (!sessionManager.isUnlocked(vaultId)) {
                _state.value = VaultReaderState.Error("Vault is locked")
                return@launch
            }
            val s = sessionManager.requireUnlocked(vaultId)
            store = s
            val entry = s.listEntries().find { it.fileId.contentEquals(fileId) }
            if (entry == null) {
                _state.value = VaultReaderState.Error("File not found in this vault")
                return@launch
            }
            if (entry.format in VAULT_AUDIO_FORMAT_NAMES) {
                _state.value = VaultReaderState.WrongScreen("This is an audio file — open it from the player instead")
                return@launch
            }
            _bookmarks.value = entry.bookmarks
            _highlights.value = entry.highlights

            val r = s.openReader(fileId)
            reader = r
            when (entry.format) {
                "EPUB" -> readiumProvider.open(r, fileIdHex).fold(
                    onSuccess = { pub -> publication = pub; _state.value = VaultReaderState.EpubReady(entry.title, pub) },
                    onFailure = { e ->
                        _state.value = if (e is VaultDrmProtectedException) {
                            VaultReaderState.DrmProtected(e.schemeName)
                        } else {
                            VaultReaderState.Error(e.message ?: "Could not open EPUB")
                        }
                    },
                )
                "PDF" -> _state.value = VaultReaderState.PdfReady(entry.title)
                else -> _state.value = VaultReaderState.Error("Unsupported format: ${entry.format}")
            }
        }
    }

    override fun onCleared() {
        publication?.close()
        reader?.close()
    }

    // ── Reading settings ──────────────────────────────────────────────────────

    fun onThemeChanged(theme: ReadingTheme) {
        _settings.update { it.copy(theme = theme) }
    }

    fun onFontSizeChanged(size: Float) {
        _settings.update { it.copy(fontSize = size.coerceIn(0.8f, 2.0f)) }
    }

    fun onFontFamilyChanged(family: VaultReaderFontFamily) {
        _settings.update {
            it.copy(
                fontFamily = family,
                // OpenDyslexic bundles a sensible line-spacing default with the
                // font itself (#423) — dyslexia-friendly typography guidance
                // recommends both together. Any other family leaves the user's
                // current line spacing untouched.
                lineSpacing = if (family == VaultReaderFontFamily.OPEN_DYSLEXIC) {
                    VAULT_DYSLEXIA_FRIENDLY_LINE_SPACING
                } else {
                    it.lineSpacing
                },
            )
        }
    }

    fun onLineSpacingChanged(spacing: Float) {
        _settings.update { it.copy(lineSpacing = spacing.coerceIn(1.0f, 2.5f)) }
    }

    fun onWarmthChanged(warmth: Float) {
        _settings.update { it.copy(warmth = warmth.coerceIn(0f, 1f)) }
    }

    fun onScrollModeChanged(mode: VaultScrollMode) {
        _settings.update { it.copy(scrollMode = mode) }
    }

    // ── Position tracking ────────────────────────────────────────────────────

    fun onEpubPositionChanged(locatorJson: String) {
        _currentPositionRef.value = locatorJson
    }

    fun onPdfPageChanged(pageIndex: Int) {
        _currentPositionRef.value = "page:$pageIndex"
    }

    // ── Bookmarks ─────────────────────────────────────────────────────────────

    /** Bookmarks the reader's current position — a no-op if no position has
     * been reported yet (e.g. tapped before the EPUB navigator/PDF renderer
     * finished its first layout pass). */
    fun addBookmark(label: String? = null) {
        val ref = _currentPositionRef.value ?: return
        val s = store ?: return
        viewModelScope.launch {
            val bookmark = s.addBookmark(fileId, ref, label)
            _bookmarks.update { it + bookmark }
        }
    }

    fun removeBookmark(id: Long) {
        val s = store ?: return
        viewModelScope.launch {
            s.removeBookmark(fileId, id)
            _bookmarks.update { list -> list.filterNot { it.id == id } }
        }
    }

    fun updateBookmarkNote(id: Long, note: String?) {
        val s = store ?: return
        viewModelScope.launch {
            s.updateBookmarkNote(fileId, id, note)
            _bookmarks.update { list -> list.map { if (it.id == id) it.copy(note = note) else it } }
        }
    }

    /** Requests navigation to [positionRef] — the screen picks up
     * [pendingNavigationRef] and forwards it to the active renderer. */
    fun navigateToBookmark(positionRef: String) {
        _pendingNavigationRef.value = positionRef
    }

    fun clearPendingNavigation() {
        _pendingNavigationRef.value = null
    }

    // ── Highlights (EPUB only — matches feature:reader's own PDF gap) ─────────

    fun addHighlight(positionRef: String, text: String, colorHex: String = "#FFE066") {
        val s = store ?: return
        viewModelScope.launch {
            val highlight = s.addHighlight(fileId, positionRef, text, colorHex)
            _highlights.update { it + highlight }
        }
    }

    fun removeHighlight(id: Long) {
        val s = store ?: return
        viewModelScope.launch {
            s.removeHighlight(fileId, id)
            _highlights.update { list -> list.filterNot { it.id == id } }
        }
    }
}
