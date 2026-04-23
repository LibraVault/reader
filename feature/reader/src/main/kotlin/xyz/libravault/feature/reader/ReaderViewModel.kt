package xyz.libravault.feature.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.libravault.core.domain.model.Bookmark
import xyz.libravault.core.domain.model.Highlight
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.ReadingProgress
import xyz.libravault.core.domain.usecase.AddBookmarkUseCase
import xyz.libravault.core.domain.usecase.AddHighlightUseCase
import xyz.libravault.core.domain.usecase.DeleteBookmarkUseCase
import xyz.libravault.core.domain.usecase.DeleteHighlightUseCase
import xyz.libravault.core.domain.usecase.GetLibraryItemUseCase
import xyz.libravault.core.domain.usecase.GetReadingProgressUseCase
import xyz.libravault.core.domain.usecase.ObserveBookmarksUseCase
import xyz.libravault.core.domain.usecase.ObserveHighlightsUseCase
import xyz.libravault.core.domain.usecase.SaveReadingProgressUseCase
import xyz.libravault.core.logger.LibravaultLogger
import java.time.Instant
import javax.inject.Inject

data class ReaderUiState(
    val item: LibraryItem?          = null,
    val progress: ReadingProgress?  = null,
    val settings: ReaderSettings    = ReaderSettings(),
    val isLoading: Boolean          = true,
    val error: String?              = null,
    val showToolbar: Boolean        = true,
    val showSettingsSheet: Boolean  = false,
    val showBookmarksSheet: Boolean = false,
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getItem: GetLibraryItemUseCase,
    private val getProgress: GetReadingProgressUseCase,
    private val saveProgress: SaveReadingProgressUseCase,
    private val observeBookmarks: ObserveBookmarksUseCase,
    private val addBookmark: AddBookmarkUseCase,
    private val deleteBookmark: DeleteBookmarkUseCase,
    private val observeHighlights: ObserveHighlightsUseCase,
    private val addHighlight: AddHighlightUseCase,
    private val deleteHighlight: DeleteHighlightUseCase,
    private val logger: LibravaultLogger,
) : ViewModel() {

    // itemId comes from navigation back-stack
    private val itemId: Long? = savedStateHandle.get<Long>("itemId")?.takeIf { it > 0 }

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    val bookmarks: StateFlow<List<Bookmark>> = (itemId?.let { observeBookmarks(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val highlights: StateFlow<List<Highlight>> = (itemId?.let { observeHighlights(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val id = itemId ?: run {
                _uiState.value = _uiState.value.copy(isLoading = false)
                return@launch
            }
            val item = getItem(id)
            if (item == null) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "Item not found.")
                return@launch
            }
            val progress = getProgress(id)
            _uiState.value = _uiState.value.copy(
                item      = item,
                progress  = progress,
                isLoading = false,
            )
            logger.i("Reader", "Opened: ${item.title}")
        }
    }

    // ── Progress ─────────────────────────────────────────────────────────────

    /** Called by EPUB navigator on position change (CFI string). */
    fun onEpubPositionChanged(cfi: String) {
        viewModelScope.launch {
            val id = itemId ?: return@launch
            saveProgress(
                ReadingProgress(
                    itemId      = id,
                    positionCfi = cfi,
                    lastReadAt  = Instant.now(),
                )
            )
        }
    }

    /** Called by PDF viewer on page change. */
    fun onPdfPageChanged(pageIndex: Int) {
        viewModelScope.launch {
            val id = itemId ?: return@launch
            saveProgress(
                ReadingProgress(
                    itemId     = id,
                    pageIndex  = pageIndex,
                    lastReadAt = Instant.now(),
                )
            )
        }
    }

    // ── Toolbar ──────────────────────────────────────────────────────────────

    /** Tap centre-third of screen to toggle toolbar. */
    fun onCentreTap() {
        _uiState.value = _uiState.value.copy(
            showToolbar = !_uiState.value.showToolbar
        )
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    fun showSettings() { _uiState.value = _uiState.value.copy(showSettingsSheet = true) }
    fun hideSettings() { _uiState.value = _uiState.value.copy(showSettingsSheet = false) }

    fun onThemeChanged(theme: xyz.libravault.core.ui.theme.ReadingTheme) {
        _uiState.value = _uiState.value.copy(
            settings = _uiState.value.settings.copy(theme = theme)
        )
    }

    fun onFontSizeChanged(size: Float) {
        _uiState.value = _uiState.value.copy(
            settings = _uiState.value.settings.copy(fontSize = size.coerceIn(0.8f, 2.0f))
        )
    }

    fun onFontFamilyChanged(family: FontFamily) {
        _uiState.value = _uiState.value.copy(
            settings = _uiState.value.settings.copy(fontFamily = family)
        )
    }

    fun onScrollModeChanged(mode: ScrollMode) {
        _uiState.value = _uiState.value.copy(
            settings = _uiState.value.settings.copy(scrollMode = mode)
        )
    }

    // ── Bookmarks ─────────────────────────────────────────────────────────────

    fun showBookmarks() { _uiState.value = _uiState.value.copy(showBookmarksSheet = true) }
    fun hideBookmarks() { _uiState.value = _uiState.value.copy(showBookmarksSheet = false) }

    fun addBookmark(positionRef: String, label: String? = null) {
        viewModelScope.launch {
            val id = itemId ?: return@launch
            addBookmark(Bookmark(itemId = id, positionRef = positionRef, label = label))
            logger.i("Reader", "Bookmark added at $positionRef")
        }
    }

    fun removeBookmark(id: Long) {
        viewModelScope.launch { deleteBookmark(id) }
    }

    // ── Highlights ────────────────────────────────────────────────────────────

    fun addHighlight(positionRef: String, text: String, colorHex: String = "#FFE066") {
        viewModelScope.launch {
            addHighlight(
                Highlight(
                    itemId          = itemId ?: return@launch,
                    positionRef     = positionRef,
                    highlightedText = text,
                    colorHex        = colorHex,
                )
            )
        }
    }

    fun removeHighlight(id: Long) {
        viewModelScope.launch { deleteHighlight(id) }
    }
}
