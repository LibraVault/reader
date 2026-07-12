package xyz.libravault.feature.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
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
import xyz.libravault.core.domain.usecase.UpdateBookmarkNoteUseCase
import xyz.libravault.core.domain.usecase.DeleteHighlightUseCase
import xyz.libravault.core.domain.usecase.GetLibraryItemUseCase
import xyz.libravault.core.storage.usecase.OpenFileUseCase
import xyz.libravault.core.domain.usecase.GetReadingProgressUseCase
import xyz.libravault.core.domain.usecase.ObserveBookmarksUseCase
import xyz.libravault.core.domain.usecase.ObserveHighlightsUseCase
import xyz.libravault.core.domain.usecase.SaveReadingProgressUseCase
import xyz.libravault.core.logger.LibravaultLogger
import xyz.libravault.feature.player.service.PlaybackStateHolder
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
    /** Set briefly after a bookmark is added; the screen shows a confirmation toast. */
    val lastAddedBookmarkId: Long?  = null,
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getItem: GetLibraryItemUseCase,
    private val openFile: OpenFileUseCase,
    private val getProgress: GetReadingProgressUseCase,
    private val saveProgress: SaveReadingProgressUseCase,
    private val observeBookmarks: ObserveBookmarksUseCase,
    private val addBookmark: AddBookmarkUseCase,
    private val deleteBookmark: DeleteBookmarkUseCase,
    private val updateBookmarkNote: UpdateBookmarkNoteUseCase,
    private val observeHighlights: ObserveHighlightsUseCase,
    private val addHighlight: AddHighlightUseCase,
    private val deleteHighlight: DeleteHighlightUseCase,
    private val logger: LibravaultLogger,
    private val playbackStateHolder: PlaybackStateHolder,
    private val controllerFuture: ListenableFuture<MediaController>,
) : ViewModel() {

    private var controller: MediaController? = null

    // itemId comes from navigation back-stack
    private val itemId: Long? = savedStateHandle.get<Long>("itemId")?.takeIf { it > 0 }

    /** Audiobook playback state — drives the mini-player overlay in the reader. */
    val nowPlaying: StateFlow<PlaybackStateHolder.State> = playbackStateHolder.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaybackStateHolder.State())

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    val bookmarks: StateFlow<List<Bookmark>> = (itemId?.let { observeBookmarks(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val highlights: StateFlow<List<Highlight>> = (itemId?.let { observeHighlights(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        controllerFuture.addListener(
            { runCatching { controller = controllerFuture.get() } },
            MoreExecutors.directExecutor(),
        )
        viewModelScope.launch {
            if (itemId != null) {
                // Normal library flow — load by Room ID
                val item = getItem(itemId)
                if (item == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Item not found.")
                    return@launch
                }
                val progress = getProgress(itemId)
                _uiState.value = _uiState.value.copy(
                    item      = item,
                    progress  = progress,
                    isLoading = false,
                )
                logger.i("Reader", "Opened from library: ${item.title}")
            } else {
                // External intent flow — resolve URI to a transient LibraryItem
                val rawUri = savedStateHandle.get<String>("encodedUri")
                    ?.let { android.net.Uri.parse(android.net.Uri.decode(it)) }
                if (rawUri == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "No file specified.")
                    return@launch
                }
                val item = openFile(rawUri)
                if (item == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Unsupported file format.")
                    return@launch
                }
                _uiState.value = _uiState.value.copy(
                    item      = item,
                    progress  = null,
                    isLoading = false,
                )
                logger.i("Reader", "Opened from external intent: ${item.title}")
            }
        }
    }

    // ── Progress ─────────────────────────────────────────────────────────────

    /** Called by EPUB navigator on position change (CFI string). */
    fun onEpubPositionChanged(cfi: String) {
        val id = itemId ?: return
        val newProgress = ReadingProgress(itemId = id, positionCfi = cfi, lastReadAt = Instant.now())
        _uiState.value = _uiState.value.copy(progress = newProgress)
        viewModelScope.launch {
            saveProgress(newProgress)
        }
    }

    fun onPdfPageChanged(pageIndex: Int) {
        val id = itemId ?: return
        val newProgress = ReadingProgress(itemId = id, pageIndex = pageIndex, lastReadAt = Instant.now())
        _uiState.value = _uiState.value.copy(progress = newProgress)
        viewModelScope.launch {
            saveProgress(newProgress)
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

    fun increaseFontSize() = onFontSizeChanged(_uiState.value.settings.fontSize + 0.1f)
    fun decreaseFontSize() = onFontSizeChanged(_uiState.value.settings.fontSize - 0.1f)

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

    fun onLineSpacingChanged(spacing: Float) {
        _uiState.value = _uiState.value.copy(
            settings = _uiState.value.settings.copy(lineSpacing = spacing.coerceIn(1.0f, 2.5f))
        )
    }

    // ── Bookmarks ─────────────────────────────────────────────────────────────

    fun showBookmarks() { _uiState.value = _uiState.value.copy(showBookmarksSheet = true) }
    fun hideBookmarks() { _uiState.value = _uiState.value.copy(showBookmarksSheet = false) }

    fun addBookmark(positionRef: String, label: String? = null) {
        val id = itemId ?: return
        viewModelScope.launch {
            val newId = addBookmark(Bookmark(itemId = id, positionRef = positionRef, label = label))
            _uiState.value = _uiState.value.copy(lastAddedBookmarkId = newId)
            logger.i("Reader", "Bookmark added at $positionRef (id=$newId)")
        }
    }

    fun clearBookmarkToast() {
        if (_uiState.value.lastAddedBookmarkId != null) {
            _uiState.value = _uiState.value.copy(lastAddedBookmarkId = null)
        }
    }

    fun removeBookmark(id: Long) {
        viewModelScope.launch { deleteBookmark(id) }
    }

    fun updateBookmarkNote(id: Long, note: String?) {
        viewModelScope.launch { updateBookmarkNote(id, note) }
    }

    // ── Highlights ────────────────────────────────────────────────────────────

    fun addHighlight(positionRef: String, text: String, colorHex: String = "#FFE066") {
        val id = itemId ?: return
        viewModelScope.launch {
            addHighlight(
                Highlight(
                    itemId          = id,
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

    // ── Audiobook mini-player controls ────────────────────────────────────────

    fun pauseAudiobook() {
        // Pause the controller if it's still playing. If Android's audio focus system
        // already auto-paused ExoPlayer (because TTS requested focus), ctrl.isPlaying
        // is already false — but we still need to update PlaybackStateHolder so the
        // Library mini-player icon reflects the paused state immediately.
        controller?.let { if (it.isPlaying) it.pause() }
        val current = playbackStateHolder.state.value
        if (current.itemId != null) {
            playbackStateHolder.update(
                itemId       = current.itemId,
                title        = current.title,
                author       = current.author,
                coverArtPath = current.coverArtPath,
                isPlaying    = false,
            )
        }
    }

    fun playPauseAudiobook() {
        val ctrl = controller ?: return
        val wasPlaying = ctrl.isPlaying
        if (wasPlaying) ctrl.pause() else ctrl.play()
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

    fun seekBackAudiobook()    { controller?.seekBack() }
    fun seekForwardAudiobook() { controller?.seekForward() }
    fun skipPreviousAudiobook() { controller?.seekToPreviousMediaItem() }
    fun skipNextAudiobook()     { controller?.seekToNextMediaItem() }
}
