package xyz.libravault.feature.reader

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
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
import xyz.libravault.core.domain.usecase.GetVaultFolderUseCase
import xyz.libravault.core.storage.usecase.OpenFileUseCase
import xyz.libravault.core.domain.usecase.GetReadingProgressUseCase
import xyz.libravault.core.domain.usecase.ObserveBookmarksUseCase
import xyz.libravault.core.domain.usecase.ObserveHighlightsUseCase
import xyz.libravault.core.domain.usecase.SaveReadingProgressUseCase
import xyz.libravault.core.logger.LibravaultLogger
import xyz.libravault.core.tts.TtsEngineProvider
import xyz.libravault.core.tts.TtsState
import xyz.libravault.core.tts.TtsStatus
import xyz.libravault.feature.player.service.PlaybackStateHolder
import xyz.libravault.feature.player.service.SeekClamp
import xyz.libravault.feature.player.service.SkipDurationPreference
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
    val showTocSheet: Boolean       = false,
    /** Set briefly after a bookmark is added; the screen shows a confirmation toast. */
    val lastAddedBookmarkId: Long?  = null,
    /** The item's vault folder SAF tree URI — used by the Markdown reader to resolve
     *  relative image references (see MarkdownAssetResolver). Null for items opened
     *  via an external intent (no vault association) or non-Markdown formats. */
    val vaultTreeUri: android.net.Uri? = null,
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getItem: GetLibraryItemUseCase,
    private val getVaultFolder: GetVaultFolderUseCase,
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
    private val ttsEngineProvider: TtsEngineProvider,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private var controller: MediaController? = null

    // itemId comes from navigation back-stack
    private val itemId: Long? = savedStateHandle.get<Long>("itemId")?.takeIf { it > 0 }

    /** Audiobook playback state — drives the mini-player overlay in the reader. */
    val nowPlaying: StateFlow<PlaybackStateHolder.State> = playbackStateHolder.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaybackStateHolder.State())

    /**
     * Read Aloud (TTS) state — drives the reader's own mini-bar (EPUB #137, Markdown #276).
     * `Eagerly` rather than `WhileSubscribed`, unlike this ViewModel's other exposed
     * flows: [toggleReadAloudPlayPause] reads `.value` synchronously off the UI thread,
     * and `WhileSubscribed` would leave that read stuck at the seed [TtsState] default
     * whenever nothing currently collects this flow (e.g. between the mini-bar
     * appearing and Compose's `collectAsState` establishing its subscription).
     */
    val readAloudState: StateFlow<TtsState> = ttsEngineProvider.engine
        .flatMapLatest { it.state }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TtsState())

    // Supplies the next chapter's text when the current utterance finishes naturally.
    // Non-null only while a Read Aloud session set up by startReadAloud is active; null
    // makes advanceOnCompletion() a no-op so unrelated completion events (e.g. voice
    // previews elsewhere) don't get misread as "advance the book".
    private var readAloudNextChapterProvider: (suspend () -> String?)? = null

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
        // Lives for the ViewModel's lifetime rather than being started/stopped per
        // Read Aloud session — flatMapLatest re-subscribes automatically if the user
        // switches TTS engine (Settings) mid-session. advanceOnCompletion() no-ops
        // when readAloudNextChapterProvider is null, so this is harmless outside of
        // an active Read Aloud session.
        viewModelScope.launch {
            ttsEngineProvider.engine.flatMapLatest { it.completionEvent }.collect {
                advanceOnCompletion()
            }
        }
        // #280 — TtsAudioFocusManager can stop() the engine directly (e.g. an
        // audiobook resumed from the lockscreen while Read Aloud is speaking),
        // bypassing stopReadAloud() entirely. Without this, readAloudNextChapterProvider
        // would stay set after such an external stop, ready to misread some later,
        // unrelated completion event as "advance the book". Keyed off stopEvent
        // rather than diffing `state` for a PLAYING/PAUSED -> IDLE edge: natural
        // completion (advanceOnCompletion) *also* transitions through IDLE on every
        // chapter, and that transition races the completionEvent collector below on
        // the same underlying flows — a prior version of this fix nulled the
        // provider before advanceOnCompletion() got to read it, breaking normal
        // chapter-to-chapter advancing. stop() is never called by the natural
        // completion path in either engine, so stopEvent can't collide with it.
        viewModelScope.launch {
            ttsEngineProvider.engine.flatMapLatest { it.stopEvent }.collect {
                readAloudNextChapterProvider = null
            }
        }
        viewModelScope.launch {
            if (itemId != null) {
                // Normal library flow — load by Room ID
                val item = getItem(itemId)
                if (item == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Item not found.")
                    return@launch
                }
                val progress = getProgress(itemId)
                val vaultTreeUri = if (item.format == xyz.libravault.core.domain.model.MediaFormat.MARKDOWN) {
                    getVaultFolder(item.vaultFolderId)?.uri?.let { android.net.Uri.parse(it) }
                } else {
                    null
                }
                _uiState.value = _uiState.value.copy(
                    item        = item,
                    progress    = progress,
                    isLoading   = false,
                    vaultTreeUri = vaultTreeUri,
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

    /**
     * Called by the Markdown renderer on scroll position change — a 0.0..1.0 fraction
     * through the document, not a pixel offset (see #125 / MIGRATION_6_7). A raw pixel
     * offset is only meaningful against the exact layout that produced it: change font
     * size, reading theme, or device rotation between sessions and the document
     * reflows to a different total height, landing the restored position somewhere
     * unrelated to where the reader actually stopped. A fraction survives all three,
     * matching how iOS's Markdown reader has always persisted progress.
     */
    fun onMarkdownScrollChanged(fraction: Double) {
        val id = itemId ?: return
        val newProgress = ReadingProgress(itemId = id, markdownScrollFraction = fraction, lastReadAt = Instant.now())
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

    // #421
    fun onMarginScaleChanged(scale: Float) {
        _uiState.value = _uiState.value.copy(
            settings = _uiState.value.settings.copy(marginScale = scale.coerceIn(0.5f, 2.0f))
        )
    }

    fun onJustifyTextChanged(justify: Boolean) {
        _uiState.value = _uiState.value.copy(
            settings = _uiState.value.settings.copy(justifyText = justify)
        )
    }

    fun onHyphenationChanged(hyphenation: Boolean) {
        _uiState.value = _uiState.value.copy(
            settings = _uiState.value.settings.copy(hyphenation = hyphenation)
        )
    }

    // ── Bookmarks ─────────────────────────────────────────────────────────────

    fun showBookmarks() { _uiState.value = _uiState.value.copy(showBookmarksSheet = true) }
    fun hideBookmarks() { _uiState.value = _uiState.value.copy(showBookmarksSheet = false) }

    // ── TOC (Markdown only) ───────────────────────────────────────────────────

    fun showToc() { _uiState.value = _uiState.value.copy(showTocSheet = true) }
    fun hideToc() { _uiState.value = _uiState.value.copy(showTocSheet = false) }

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
        viewModelScope.launch { updateBookmarkNote.invoke(id, note) }
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
                itemId        = current.itemId,
                vaultFolderId = current.vaultFolderId,
                filePath      = current.filePath,
                title         = current.title,
                author        = current.author,
                coverArtPath  = current.coverArtPath,
                isPlaying     = false,
            )
        }
    }

    fun playPauseAudiobook() {
        val ctrl = controller ?: return
        val wasPlaying = ctrl.isPlaying
        // Mutual exclusion (#137): resuming the audiobook is the moment audio would
        // otherwise overlap with an active Read Aloud session — stop TTS first. The
        // reverse direction (Read Aloud pausing an already-playing audiobook) is
        // handled in startReadAloud() via pauseAudiobook().
        if (!wasPlaying) stopReadAloud()
        if (wasPlaying) ctrl.pause() else ctrl.play()
        val current = playbackStateHolder.state.value
        if (current.itemId != null) {
            playbackStateHolder.update(
                itemId        = current.itemId,
                vaultFolderId = current.vaultFolderId,
                filePath      = current.filePath,
                title         = current.title,
                author        = current.author,
                coverArtPath  = current.coverArtPath,
                isPlaying     = !wasPlaying,
            )
        }
    }

    fun seekBackAudiobook()    { seekByAudiobook(-SkipDurationPreference.getSkipDurationMs(appContext)) }
    fun seekForwardAudiobook() { seekByAudiobook( SkipDurationPreference.getSkipDurationMs(appContext)) }
    fun skipPreviousAudiobook() { controller?.seekToPreviousMediaItem() }
    fun skipNextAudiobook()     { controller?.seekToNextMediaItem() }

    /**
     * See [xyz.libravault.feature.library.LibraryViewModel.seekBy] — same rationale: the
     * explicit `seekTo` honors the user's runtime `defaultSkipDurationSec` preference,
     * whereas `MediaController.seekBack`/`seekForward` defer to ExoPlayer's immutable
     * build-time seek increment. Clamp logic lives in
     * [xyz.libravault.feature.player.service.SeekClamp.clamp].
     */
    private fun seekByAudiobook(deltaMs: Long) {
        val ctrl = controller ?: return
        ctrl.seekTo(SeekClamp.clamp(ctrl.currentPosition, deltaMs, ctrl.duration))
    }

    // ── Read Aloud (EPUB TTS mini-bar, #137) ────────────────────────────────────

    /**
     * Starts (or restarts) a Read Aloud session. [getInitialText] and [getNextText]
     * are supplied by the caller rather than looked up here — [EpubReaderViewModel]
     * owns the chapter-walking text pipeline (`getChapterTextFromProgression()` /
     * `getNextChapterText()`), and it is a sibling `hiltViewModel()` scoped to
     * [ReaderScreen], not something this ViewModel can inject. Markdown (#276)
     * reuses this same entry point with its own text supplier.
     */
    fun startReadAloud(
        getInitialText: suspend () -> String?,
        getNextText: suspend () -> String?,
    ) {
        // Mutual exclusion (#137): only one thing produces audio at a time.
        pauseAudiobook()
        readAloudNextChapterProvider = getNextText
        viewModelScope.launch {
            val text = getInitialText()
            if (text != null) {
                ttsEngineProvider.engine.value.speak(text)
            } else {
                stopReadAloud()
            }
        }
    }

    fun pauseReadAloud()  { ttsEngineProvider.engine.value.pause() }
    fun resumeReadAloud() { ttsEngineProvider.engine.value.resume() }

    fun toggleReadAloudPlayPause() {
        when (readAloudState.value.status) {
            TtsStatus.PLAYING -> pauseReadAloud()
            TtsStatus.PAUSED  -> resumeReadAloud()
            else -> {}
        }
    }

    fun stopReadAloud() {
        readAloudNextChapterProvider = null
        ttsEngineProvider.engine.value.stop()
    }

    /** Auto-advance on natural utterance completion — see [readAloudNextChapterProvider]. */
    private fun advanceOnCompletion() {
        val getNext = readAloudNextChapterProvider ?: return
        viewModelScope.launch {
            val next = getNext()
            if (next != null) {
                ttsEngineProvider.engine.value.speak(next)
            } else {
                stopReadAloud()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Leaving the reader closes the EPUB publication (see EpubReaderViewModel's
        // onCleared) that readAloudNextChapterProvider depends on to fetch further
        // chapters — stop rather than let Read Aloud speak into a torn-down session.
        stopReadAloud()
    }
}
