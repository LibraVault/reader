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
import xyz.libravault.core.domain.model.AppReadingTheme
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
import xyz.libravault.core.storage.ReadingThemePreference
import xyz.libravault.core.tts.TtsDurationEstimator
import xyz.libravault.core.tts.TtsEngineProvider
import xyz.libravault.core.tts.TtsState
import xyz.libravault.core.tts.TtsStatus
import xyz.libravault.feature.player.service.PlaybackStateHolder
import xyz.libravault.feature.player.service.SeekClamp
import xyz.libravault.feature.player.service.SkipDurationPreference
import xyz.libravault.feature.player.service.SleepTimerState
import xyz.libravault.feature.reader.readaloud.ReadAloudSleepTimer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    /** Full Player-screen overlay for an active Read Aloud session (#138), opened by
     *  tapping the Read Aloud mini-bar. */
    val showReadAloudPlayer: Boolean = false,
    val showReadAloudSleepTimerSheet: Boolean = false,
)

/**
 * Read Aloud (TTS) playback progress, driving the #138 Player screen's scrubber,
 * chapter display, and sleep timer status. There's no real seekable audio stream
 * for TTS (same as iOS's `AppState`) — [elapsedMs]/[durationMs] are a wall-clock/
 * word-count estimate (see [TtsDurationEstimator]), not real playback position.
 */
data class ReadAloudPlaybackState(
    val elapsedMs: Long = 0L,
    val durationMs: Long = 0L,
    val chapterIndex: Int = 0,
    val chapterCount: Int = 0,
    val sleepTimerState: SleepTimerState = SleepTimerState.Inactive,
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

    // Symmetric supplier for the Player screen's "previous chapter" control (#138).
    // Same non-null-only-during-a-session contract as readAloudNextChapterProvider.
    private var readAloudPreviousChapterProvider: (suspend () -> String?)? = null

    // Read the chapter walker's current position synchronously (EpubReaderViewModel /
    // MarkdownReaderViewModel already track this) so the Player screen's chapter
    // display/nav can be refreshed the instant a chapter change is initiated, without
    // waiting on a suspend round-trip.
    private var readAloudChapterIndexProvider: (() -> Int)? = null
    private var readAloudChapterCountProvider: (() -> Int)? = null

    private var readAloudTickerJob: Job? = null

    // Fires by pausing the session — no volume fade, see ReadAloudSleepTimer's doc.
    private val readAloudSleepTimer = ReadAloudSleepTimer(onFire = ::pauseReadAloud)

    private val _readAloudPlayback = MutableStateFlow(ReadAloudPlaybackState())
    val readAloudPlayback: StateFlow<ReadAloudPlaybackState> = _readAloudPlayback.asStateFlow()

    // #428 — seeded from the global default rather than ReaderSettings()'s own
    // hardcoded DARK, so a book opens in whatever theme Settings has configured.
    private val _uiState = MutableStateFlow(
        ReaderUiState(settings = ReaderSettings(theme = ReadingThemePreference.read(appContext).toReadingTheme()))
    )
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
                readAloudPreviousChapterProvider = null
                readAloudChapterIndexProvider = null
                readAloudChapterCountProvider = null
                stopReadAloudTicker()
            }
        }
        // Relays the sleep timer's own state into readAloudPlayback so the Player
        // screen can render it from one flow, the same way SleepTimerState already
        // rides along PlayerUiState for the audiobook player.
        viewModelScope.launch {
            readAloudSleepTimer.state.collect { sleepState ->
                _readAloudPlayback.value = _readAloudPlayback.value.copy(sleepTimerState = sleepState)
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
        // #428 — write through so the choice survives closing the reader and
        // matches whatever Settings shows as the default.
        ReadingThemePreference.write(appContext, theme.toAppReadingTheme())
    }

    fun onFontSizeChanged(size: Float) {
        _uiState.value = _uiState.value.copy(
            settings = _uiState.value.settings.copy(fontSize = size.coerceIn(0.8f, 2.0f))
        )
    }

    fun increaseFontSize() = onFontSizeChanged(_uiState.value.settings.fontSize + 0.1f)
    fun decreaseFontSize() = onFontSizeChanged(_uiState.value.settings.fontSize - 0.1f)

    fun onFontFamilyChanged(family: FontFamily) {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(
            settings = current.copy(
                fontFamily = family,
                // OpenDyslexic bundles a sensible line-spacing default with the
                // font itself (#423) — dyslexia-friendly typography guidance
                // recommends both together. Any other family leaves the user's
                // current line spacing untouched.
                lineSpacing = if (family == FontFamily.OPEN_DYSLEXIC) {
                    DYSLEXIA_FRIENDLY_LINE_SPACING
                } else {
                    current.lineSpacing
                },
            )
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

    fun onWarmthChanged(warmth: Float) {
        _uiState.value = _uiState.value.copy(
            settings = _uiState.value.settings.copy(warmth = warmth.coerceIn(0f, 1f))
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
     *
     * [getPreviousText]/[chapterIndex]/[chapterCount] are optional (#138's Player
     * screen chapter nav/display) so existing EPUB/Markdown call sites and the #137
     * mini-bar-only tests above don't need to supply them.
     */
    fun startReadAloud(
        getInitialText: suspend () -> String?,
        getNextText: suspend () -> String?,
        getPreviousText: suspend () -> String? = { null },
        chapterIndex: () -> Int = { 0 },
        chapterCount: () -> Int = { 0 },
    ) {
        // Mutual exclusion (#137): only one thing produces audio at a time.
        pauseAudiobook()
        readAloudNextChapterProvider = getNextText
        readAloudPreviousChapterProvider = getPreviousText
        readAloudChapterIndexProvider = chapterIndex
        readAloudChapterCountProvider = chapterCount
        viewModelScope.launch {
            val text = getInitialText()
            if (text != null) {
                beginReadAloudChapter(text)
                ttsEngineProvider.engine.value.speak(text)
            } else {
                stopReadAloud()
            }
        }
    }

    fun pauseReadAloud()  { ttsEngineProvider.engine.value.pause(); stopReadAloudTicker() }
    fun resumeReadAloud() { ttsEngineProvider.engine.value.resume(); startReadAloudTicker() }

    fun toggleReadAloudPlayPause() {
        when (readAloudState.value.status) {
            TtsStatus.PLAYING -> pauseReadAloud()
            TtsStatus.PAUSED  -> resumeReadAloud()
            else -> {}
        }
    }

    fun stopReadAloud() {
        readAloudNextChapterProvider = null
        readAloudPreviousChapterProvider = null
        readAloudChapterIndexProvider = null
        readAloudChapterCountProvider = null
        stopReadAloudTicker()
        readAloudSleepTimer.cancel()
        _readAloudPlayback.value = ReadAloudPlaybackState()
        _uiState.value = _uiState.value.copy(
            showReadAloudPlayer = false,
            showReadAloudSleepTimerSheet = false,
        )
        ttsEngineProvider.engine.value.stop()
    }

    /** Auto-advance on natural utterance completion — see [readAloudNextChapterProvider]. */
    private fun advanceOnCompletion() {
        val getNext = readAloudNextChapterProvider ?: return
        viewModelScope.launch {
            val next = getNext()
            if (next != null) {
                beginReadAloudChapter(next)
                ttsEngineProvider.engine.value.speak(next)
            } else {
                stopReadAloud()
            }
        }
    }

    // ── Read Aloud Player screen (#138) ─────────────────────────────────────────

    fun showReadAloudPlayer() { _uiState.value = _uiState.value.copy(showReadAloudPlayer = true) }
    fun hideReadAloudPlayer() { _uiState.value = _uiState.value.copy(showReadAloudPlayer = false) }

    /**
     * Resets [_readAloudPlayback] for a chapter that's about to start speaking —
     * called from [startReadAloud], [advanceOnCompletion], and the manual chapter-nav
     * functions below, so all three advance-into-a-chapter paths agree on the
     * estimate/chapter bookkeeping instead of duplicating it.
     */
    private fun beginReadAloudChapter(text: String) {
        val duration = TtsDurationEstimator.estimateDurationMs(text, readAloudState.value.speechRate)
        _readAloudPlayback.value = _readAloudPlayback.value.copy(
            elapsedMs    = 0L,
            durationMs   = duration,
            chapterIndex = readAloudChapterIndexProvider?.invoke() ?: 0,
            chapterCount = readAloudChapterCountProvider?.invoke() ?: 0,
        )
        startReadAloudTicker()
    }

    /**
     * Manual chapter navigation for the Player screen — mirrors iOS's
     * `skipToChapter`'s clamped-at-the-boundary contract: at the start/end of the
     * book, [readAloudPreviousChapterProvider]/[readAloudNextChapterProvider] return
     * null and this is a no-op rather than stopping the session (unlike
     * [advanceOnCompletion], where null legitimately means "book finished").
     */
    fun nextReadAloudChapter() {
        val getNext = readAloudNextChapterProvider ?: return
        viewModelScope.launch {
            val next = getNext() ?: return@launch
            beginReadAloudChapter(next)
            ttsEngineProvider.engine.value.speak(next)
        }
    }

    fun previousReadAloudChapter() {
        val getPrevious = readAloudPreviousChapterProvider ?: return
        viewModelScope.launch {
            val previous = getPrevious() ?: return@launch
            beginReadAloudChapter(previous)
            ttsEngineProvider.engine.value.speak(previous)
        }
    }

    /**
     * Scrub-bar seeking. There's still no real audio stream to seek within (see
     * [ReadAloudPlaybackState]'s doc) — this only moves the estimate, the same way
     * iOS's `AppState.seek(to:)` does for its TTS/text books.
     */
    fun seekReadAloud(positionMs: Long) {
        val current = _readAloudPlayback.value
        _readAloudPlayback.value = current.copy(elapsedMs = positionMs.coerceIn(0L, current.durationMs))
    }

    fun skipForwardReadAloud(deltaMs: Long = 30_000L)  = seekReadAloud(_readAloudPlayback.value.elapsedMs + deltaMs)
    fun skipBackwardReadAloud(deltaMs: Long = 30_000L) = seekReadAloud(_readAloudPlayback.value.elapsedMs - deltaMs)

    /**
     * Changing speed mid-chapter rescales the duration estimate (and elapsed
     * position, proportionally) rather than leaving them stale — mirrors the intent
     * of iOS's `playbackSpeed`'s `didSet`, which re-estimates duration from the full
     * chapter text and rescales elapsed to preserve the listener's fraction through
     * it. Android doesn't retain the chapter's full text here, so it scales the
     * existing estimate by the old/new speed ratio directly instead of
     * re-estimating from text — equivalent, since [TtsDurationEstimator] is linear
     * in 1/speed.
     */
    fun setReadAloudSpeed(rate: Float) {
        val oldSpeed = readAloudState.value.speechRate.takeIf { it > 0f } ?: 1f
        ttsEngineProvider.engine.value.setSpeechRate(rate)
        val current = _readAloudPlayback.value
        if (current.durationMs <= 0L || rate <= 0f) return
        val fraction   = current.elapsedMs.toFloat() / current.durationMs
        val newDuration = (current.durationMs * (oldSpeed / rate)).toLong()
        _readAloudPlayback.value = current.copy(
            durationMs = newDuration,
            elapsedMs  = (fraction * newDuration).toLong(),
        )
    }

    // ── Read Aloud sleep timer (#138) ───────────────────────────────────────────
    // Separate ReadAloudSleepTimer instance (see its doc) — just pauses on fire,
    // no volume fade, since there's no ExoPlayer to fade.

    fun showReadAloudSleepTimer() { _uiState.value = _uiState.value.copy(showReadAloudSleepTimerSheet = true) }
    fun hideReadAloudSleepTimer() { _uiState.value = _uiState.value.copy(showReadAloudSleepTimerSheet = false) }

    fun startReadAloudSleepTimer(durationMs: Long) {
        readAloudSleepTimer.start(durationMs, viewModelScope)
        hideReadAloudSleepTimer()
    }

    /** "End of chapter" preset — fires after however long is left in the current
     *  chapter's estimate, rather than a fixed duration. */
    fun startReadAloudSleepTimerEndOfChapter() {
        val current = _readAloudPlayback.value
        readAloudSleepTimer.start((current.durationMs - current.elapsedMs).coerceAtLeast(0L), viewModelScope)
        hideReadAloudSleepTimer()
    }

    fun cancelReadAloudSleepTimer() = readAloudSleepTimer.cancel()

    private fun startReadAloudTicker() {
        stopReadAloudTicker()
        readAloudTickerJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000L)
                val current = _readAloudPlayback.value
                val advanceMs = (1_000L * readAloudState.value.speechRate).toLong()
                _readAloudPlayback.value = current.copy(
                    elapsedMs = (current.elapsedMs + advanceMs).coerceAtMost(current.durationMs),
                )
            }
        }
    }

    private fun stopReadAloudTicker() {
        readAloudTickerJob?.cancel()
        readAloudTickerJob = null
    }

    override fun onCleared() {
        super.onCleared()
        // Leaving the reader closes the EPUB publication (see EpubReaderViewModel's
        // onCleared) that readAloudNextChapterProvider depends on to fetch further
        // chapters — stop rather than let Read Aloud speak into a torn-down session.
        stopReadAloud()
    }
}

/**
 * [AppReadingTheme] (`core:domain`, KMP-safe) <-> [xyz.libravault.core.ui.theme.ReadingTheme]
 * (`core:ui`, this reader's own settings type) — duplicated per module rather than a new
 * cross-module dependency, same rationale [VaultReaderSettings] documents for its own
 * duplication of [ReaderSettings].
 */
private fun AppReadingTheme.toReadingTheme(): xyz.libravault.core.ui.theme.ReadingTheme = when (this) {
    AppReadingTheme.DARK   -> xyz.libravault.core.ui.theme.ReadingTheme.DARK
    AppReadingTheme.LIGHT  -> xyz.libravault.core.ui.theme.ReadingTheme.LIGHT
    AppReadingTheme.SEPIA  -> xyz.libravault.core.ui.theme.ReadingTheme.SEPIA
    AppReadingTheme.AMOLED -> xyz.libravault.core.ui.theme.ReadingTheme.AMOLED
    AppReadingTheme.SYSTEM -> xyz.libravault.core.ui.theme.ReadingTheme.SYSTEM
}

private fun xyz.libravault.core.ui.theme.ReadingTheme.toAppReadingTheme(): AppReadingTheme = when (this) {
    xyz.libravault.core.ui.theme.ReadingTheme.DARK   -> AppReadingTheme.DARK
    xyz.libravault.core.ui.theme.ReadingTheme.LIGHT  -> AppReadingTheme.LIGHT
    xyz.libravault.core.ui.theme.ReadingTheme.SEPIA  -> AppReadingTheme.SEPIA
    xyz.libravault.core.ui.theme.ReadingTheme.AMOLED -> AppReadingTheme.AMOLED
    xyz.libravault.core.ui.theme.ReadingTheme.SYSTEM -> AppReadingTheme.SYSTEM
}
