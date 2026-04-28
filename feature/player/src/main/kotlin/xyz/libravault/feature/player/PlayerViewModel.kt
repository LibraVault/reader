package xyz.libravault.feature.player

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.ListeningProgress
import xyz.libravault.core.domain.usecase.AddBookmarkUseCase
import xyz.libravault.core.domain.usecase.GetLibraryItemUseCase
import xyz.libravault.core.domain.usecase.GetListeningProgressUseCase
import xyz.libravault.core.domain.usecase.ObserveBookmarksUseCase
import xyz.libravault.core.domain.usecase.SaveListeningProgressUseCase
import xyz.libravault.core.domain.model.Bookmark
import xyz.libravault.core.domain.model.snapPlaybackSpeed
import xyz.libravault.core.logger.LibravaultLogger
import xyz.libravault.feature.player.service.Chapter
import xyz.libravault.feature.player.service.ChapterExtractor
import xyz.libravault.feature.player.service.PlaybackStateHolder
import xyz.libravault.feature.player.service.SleepTimer
import xyz.libravault.feature.player.service.SleepTimerState
import java.time.Instant
import javax.inject.Inject

data class PlayerUiState(
    val item: LibraryItem?          = null,
    val isLoading: Boolean          = true,
    val error: String?              = null,
    // Playback
    val isPlaying: Boolean          = false,
    val positionMs: Long            = 0L,
    val durationMs: Long            = 0L,
    val bufferedMs: Long            = 0L,
    val playbackSpeed: Float        = 1.0f,
    val savedStartPositionMs: Long  = 0L,   // restored from DB; used once on first play
    // Chapters
    val chapters: List<Chapter>     = emptyList(),
    val currentChapterIndex: Int    = 0,
    // Sleep timer
    val sleepTimerState: SleepTimerState = SleepTimerState.Inactive,
    val showSleepTimerSheet: Boolean = false,
    // Bookmarks
    val showBookmarksSheet: Boolean = false,
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getItem: GetLibraryItemUseCase,
    private val getProgress: GetListeningProgressUseCase,
    private val saveProgress: SaveListeningProgressUseCase,
    private val observeBookmarks: ObserveBookmarksUseCase,
    private val addBookmark: AddBookmarkUseCase,
    private val controllerFuture: ListenableFuture<MediaController>,
    private val chapterExtractor: ChapterExtractor,
    private val sleepTimer: SleepTimer,
    private val logger: LibravaultLogger,
    private val playbackStateHolder: PlaybackStateHolder,
) : ViewModel() {

    companion object {
        private const val TAG            = "PlayerViewModel"
        private const val SKIP_MS        = 30_000L   // 30-second skip
        private const val PROGRESS_SAVE_INTERVAL_MS = 5_000L
        private const val MAX_RETRIES    = 4
        private const val RETRY_DELAY_MS = 500L
    }

    private val itemId: Long? = savedStateHandle.get<Long>("itemId")?.takeIf { it > 0 }

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    val bookmarks: StateFlow<List<Bookmark>> = (itemId?.let { observeBookmarks(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var controller: MediaController? = null
    private var positionPollingJob: Job?     = null
    private var progressSaveJob: Job?        = null
    private var playedItemUri: String?       = null
    private var retryJob: Job?               = null

    // Observe sleep timer state
    init {
        viewModelScope.launch {
            sleepTimer.state.collect { timerState ->
                _uiState.value = _uiState.value.copy(sleepTimerState = timerState)
            }
        }
        loadItem()
        connectController()
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    private fun loadItem() {
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
            val savedPositionMs = getProgress(id)?.positionMs ?: 0L
            _uiState.value = _uiState.value.copy(
                item                 = item,
                isLoading            = false,
                savedStartPositionMs = savedPositionMs,
            )
            logger.i(TAG, "Loaded: ${item.title} — resume at ${savedPositionMs}ms")
            // If the controller was already connected before loadItem() finished,
            // connectController's play() attempt was a no-op (item was null). Trigger it now.
            controller?.let { play(android.net.Uri.parse(item.filePath), startPositionMs = savedPositionMs) }
        }
    }

    /**
     * Connects to [PlaybackService] via [MediaController] with retry.
     *
     * Uses the non-blocking addListener() pattern (ListenableFuture) so the
     * main thread is never blocked. On failure, retries with exponential
     * backoff so transient service unavailability is tolerated.
     */
    private fun connectController() {
        connectWithRetry()
    }

    private fun connectWithRetry(attempt: Int = 1) {
        controllerFuture.addListener({
            runCatching {
                val ctrl = controllerFuture.get()
                controller = ctrl
                controller?.addListener(playerListener)
                logger.i(TAG, "MediaController connected (attempt $attempt)")
                // Clear any previous error state on successful reconnect
                if (_uiState.value.error != null) {
                    _uiState.value = _uiState.value.copy(error = null)
                }
                // Item may have loaded before the controller was ready — play now.
                // Check if the controller already has this item loaded (same URI) to avoid
                // reinitializing ExoPlayer's media pipeline, which causes audio stutter.
                val item = _uiState.value.item ?: return@addListener
                val uri = android.net.Uri.parse(item.filePath)
                val savedPos = _uiState.value.savedStartPositionMs
                val currentMedia = ctrl.currentMediaItem
                if (currentMedia?.localConfiguration?.uri == uri) {
                    // Same item already loaded — seek to saved position and resume
                    ctrl.seekTo(savedPos)
                    ctrl.play()
                } else {
                    play(uri, startPositionMs = savedPos)
                }
            }.onFailure { e ->
                if (attempt >= MAX_RETRIES) {
                    logger.e(TAG, "MediaController connection exhausted after $MAX_RETRIES attempts", e)
                    _uiState.value = _uiState.value.copy(error = "Playback service unavailable.")
                } else {
                    logger.w(TAG, "MediaController connection failed (attempt $attempt), retrying: ${e.message}")
                    // Schedule retry with exponential backoff: 500ms, 1s, 2s, 4s
                    retryJob = viewModelScope.launch {
                        delay(RETRY_DELAY_MS * (1L shl (attempt - 1)))
                        connectWithRetry(attempt + 1)
                    }
                }
            }
        }, MoreExecutors.directExecutor())
    }

    // ── Player listener ───────────────────────────────────────────────────────

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
            if (isPlaying) startPolling() else stopPolling()
        }

        override fun onPlaybackParametersChanged(
            playbackParameters: androidx.media3.common.PlaybackParameters,
        ) {
            _uiState.value = _uiState.value.copy(
                playbackSpeed = snapPlaybackSpeed(playbackParameters.speed)
            )
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateChapters()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            logger.e(TAG, "Player error: ${error.message}")
            _uiState.value = _uiState.value.copy(error = "Playback error: ${error.message}")
        }

        override fun onPlayerErrorChanged(error: androidx.media3.common.PlaybackException?) {
            if (error == null) {
                _uiState.value = _uiState.value.copy(error = null)
            }
        }
    }

    // ── Playback controls ─────────────────────────────────────────────────────

    fun play(uri: Uri, startPositionMs: Long = 0L) {
        val ctrl = controller ?: return
        // Guard against duplicate play() — the same URI can be triggered from
        // loadItem(), connectController(), and PlayerScreen's LaunchedEffect.
        val uriStr = uri.toString()
        if (uriStr == playedItemUri) {
            // Same URI already loaded — just seek to the saved position
            // without resetting the media pipeline (avoids audio blip and
            // position drift from repeated setMediaItem calls).
            if (startPositionMs > 0) ctrl.seekTo(startPositionMs)
            return
        }
        playedItemUri = uriStr
        val mediaItem = MediaItem.fromUri(uri)
        ctrl.setMediaItem(mediaItem, startPositionMs)
        ctrl.prepare()
        ctrl.play()
        // Update PlaybackStateHolder immediately so the mini-player shows the new
        // item without waiting for the next polling cycle (200ms delay).
        // The polling loop in startPolling() keeps it up to date thereafter.
        val item = _uiState.value.item
        if (item != null) {
            playbackStateHolder.update(
                itemId       = item.id,
                title        = item.title,
                author       = item.author,
                coverArtPath = item.coverArtPath,
                isPlaying    = true,
            )
        }
        startProgressSaving()
        updateChapters()
    }

    fun togglePlayPause() {
        val ctrl = controller ?: return
        if (ctrl.isPlaying) ctrl.pause() else ctrl.play()
    }

    fun skipForward() {
        val ctrl = controller ?: return
        ctrl.seekTo((ctrl.currentPosition + SKIP_MS).coerceAtMost(ctrl.duration))
    }

    /**
     * Retry playback after a player error.
     * Clears the error state and re-prepares the current media item.
     * Uses the controller's current media item to avoid re-parsing URIs.
     */
    fun retryPlayback() {
        val ctrl = controller ?: return
        _uiState.value = _uiState.value.copy(error = null)
        val currentItem = ctrl.currentMediaItem ?: return
        ctrl.stop()
        ctrl.setMediaItem(currentItem)
        ctrl.prepare()
        ctrl.play()
    }

    fun skipBack() {
        val ctrl = controller ?: return
        ctrl.seekTo((ctrl.currentPosition - SKIP_MS).coerceAtLeast(0L))
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    // ── Speed ─────────────────────────────────────────────────────────────────

    /** Valid range 0.5× to 3.0× in 0.25 steps. */
    fun setSpeed(speed: Float) {
        val snapped = snapPlaybackSpeed(speed)
        controller?.setPlaybackSpeed(snapped)
    }

    // ── Chapters ──────────────────────────────────────────────────────────────

    private fun updateChapters() {
        val item = _uiState.value.item ?: return
        viewModelScope.launch {
            val duration = controller?.duration?.takeIf { it > 0 }
                ?: item.durationMs ?: return@launch
            val chapters = chapterExtractor.extract(Uri.parse(item.filePath), duration)
            val currentIdx = currentChapterIndex(controller?.currentPosition ?: 0L, chapters)
            _uiState.value = _uiState.value.copy(
                chapters            = chapters,
                currentChapterIndex = currentIdx,
                durationMs          = duration,
            )
        }
    }

    fun goToChapter(index: Int) {
        val chapters = _uiState.value.chapters
        if (index !in chapters.indices) return
        controller?.seekTo(chapters[index].startMs)
        _uiState.value = _uiState.value.copy(currentChapterIndex = index)
    }

    fun nextChapter() = goToChapter(_uiState.value.currentChapterIndex + 1)
    fun previousChapter() = goToChapter(_uiState.value.currentChapterIndex - 1)

    private fun currentChapterIndex(positionMs: Long, chapters: List<Chapter>): Int =
        chapters.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)

    // ── Sleep timer ───────────────────────────────────────────────────────────

    fun showSleepTimer()    { _uiState.value = _uiState.value.copy(showSleepTimerSheet = true) }
    fun hideSleepTimer()    { _uiState.value = _uiState.value.copy(showSleepTimerSheet = false) }

    fun startSleepTimer(durationMs: Long) {
        sleepTimer.start(durationMs, viewModelScope)
        hideSleepTimer()
        logger.i(TAG, "Sleep timer set for ${durationMs / 60_000} min")
    }

    fun startSleepTimerEndOfChapter() {
        val ctrl = controller ?: return
        val chapters  = _uiState.value.chapters
        val currentIdx = _uiState.value.currentChapterIndex
        val remaining = if (chapters.isNotEmpty() && currentIdx in chapters.indices) {
            chapters[currentIdx].endMs - ctrl.currentPosition
        } else {
            (ctrl.duration - ctrl.currentPosition).coerceAtLeast(0L)
        }
        sleepTimer.start(remaining, viewModelScope)
    }

    fun cancelSleepTimer() = sleepTimer.cancel()

    // ── Bookmarks ─────────────────────────────────────────────────────────────

    fun showBookmarks()  { _uiState.value = _uiState.value.copy(showBookmarksSheet = true) }
    fun hideBookmarks()  { _uiState.value = _uiState.value.copy(showBookmarksSheet = false) }

    fun addBookmark(label: String? = null) {
        val positionMs = controller?.currentPosition ?: return
        viewModelScope.launch {
            val id = itemId ?: return@launch
            addBookmark(
                Bookmark(
                    itemId      = id,
                    positionRef = "ms:$positionMs",
                    label       = label ?: formatPosition(positionMs),
                )
            )
            logger.i(TAG, "Bookmark added at ${formatPosition(positionMs)}")
        }
    }

    fun seekToBookmark(bookmark: Bookmark) {
        val ms = bookmark.positionRef.removePrefix("ms:").toLongOrNull() ?: return
        controller?.seekTo(ms)
        hideBookmarks()
    }

    // ── Position polling & progress saving ────────────────────────────────────

    private fun startPolling() {
        positionPollingJob?.cancel()
        positionPollingJob = viewModelScope.launch {
            while (isActive) {
                val ctrl = controller ?: break
                val pos  = ctrl.currentPosition
                val dur  = ctrl.duration.takeIf { it > 0 } ?: _uiState.value.durationMs
                val buf  = ctrl.bufferedPosition
                val chapIdx = currentChapterIndex(pos, _uiState.value.chapters)

                _uiState.value = _uiState.value.copy(
                    positionMs          = pos,
                    durationMs          = dur,
                    bufferedMs          = buf,
                    currentChapterIndex = chapIdx,
                )
                // Push state to the singleton holder for mini-player
                val item = _uiState.value.item
                if (item != null) {
                    playbackStateHolder.update(
                        itemId       = item.id,
                        title        = item.title,
                        author       = item.author,
                        coverArtPath = item.coverArtPath,
                        isPlaying    = ctrl.isPlaying,
                    )
                }
                delay(200)
            }
        }
    }

    private fun stopPolling() { positionPollingJob?.cancel() }

    private fun startProgressSaving() {
        progressSaveJob?.cancel()
        progressSaveJob = viewModelScope.launch {
            while (isActive) {
                delay(PROGRESS_SAVE_INTERVAL_MS)
                val pos     = controller?.currentPosition ?: continue
                val chapIdx = _uiState.value.currentChapterIndex
                val id      = itemId ?: continue
                saveProgress(
                    ListeningProgress(
                        itemId         = id,
                        positionMs     = pos,
                        chapterIndex   = chapIdx,
                        lastListenedAt = Instant.now(),
                    )
                )
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun formatPosition(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    override fun onCleared() {
        stopPolling()
        progressSaveJob?.cancel()
        retryJob?.cancel()
        // Save final progress — persist the last known position so the user
        // doesn't lose up to 5 seconds of progress on navigation.
        // Use NonCancellable because viewModelScope is cancelled at super.onCleared()
        // and we must guarantee this write completes before the process dies.
        val pos = controller?.currentPosition
        val id = itemId
        if (pos != null && id != null) {
            viewModelScope.launch {
                withContext(NonCancellable) {
                    saveProgress(
                        ListeningProgress(
                            itemId         = id,
                            positionMs     = pos,
                            chapterIndex   = _uiState.value.currentChapterIndex,
                            lastListenedAt = Instant.now(),
                        )
                    )
                }
            }
        }
        controller?.removeListener(playerListener)
        // Do NOT release the singleton controllerFuture here — it is @Singleton scoped
        // and shared across ViewModel instances. Releasing it cancels the future for
        // all subsequent PlayerViewModel instances, causing "Playback service unavailable".
        // The MediaController itself stays connected for background playback continuity.
        super.onCleared()
    }
}
