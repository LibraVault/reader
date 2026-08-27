package xyz.libravault.feature.player

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.libravault.core.domain.model.ContentSource
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.ListeningProgress
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.core.domain.usecase.AddBookmarkUseCase
import xyz.libravault.core.domain.usecase.DeleteBookmarkUseCase
import xyz.libravault.core.domain.usecase.UpdateBookmarkNoteUseCase
import xyz.libravault.core.domain.usecase.GetLibraryItemUseCase
import xyz.libravault.core.domain.usecase.GetListeningProgressUseCase
import xyz.libravault.core.domain.usecase.ObserveBookmarksUseCase
import xyz.libravault.core.domain.usecase.SaveListeningProgressUseCase
import xyz.libravault.core.domain.model.Bookmark
import xyz.libravault.core.domain.model.snapPlaybackSpeed
import xyz.libravault.core.logger.LibravaultLogger
import xyz.libravault.core.vaultstore.VAULT_AUDIO_FORMAT_NAMES
import xyz.libravault.core.vaultstore.VaultBookmark
import xyz.libravault.core.vaultstore.VaultLockedException
import xyz.libravault.core.vaultstore.VaultSessionManager
import xyz.libravault.core.vaultstore.hexToFileId
import xyz.libravault.feature.player.service.Chapter
import xyz.libravault.feature.player.service.ChapterExtractor
import xyz.libravault.feature.player.service.PlaybackStateHolder
import xyz.libravault.feature.player.service.SeekClamp
import xyz.libravault.feature.player.service.SleepTimer
import xyz.libravault.feature.player.service.SleepTimerState
import xyz.libravault.feature.player.service.VAULT_MEDIA_URI_SCHEME
import xyz.libravault.feature.player.service.VaultNotificationMetadataPreference
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
    /** Set briefly after a bookmark is added; the screen shows a confirmation toast. */
    val lastAddedBookmarkId: Long?  = null,
    /** Flips true once a vault-sourced session (#493) detects the vault got
     *  locked out from under it — same `wasLocked` pattern [ReaderUiState]/the
     *  deleted `VaultPlayerUiState` already use. Always false for a real-file
     *  item. The screen pops back when this flips. */
    val wasLocked: Boolean = false,
    /** True for a [ContentSource.VaultEntry]-backed item (#493) — drives
     *  `PlayerScreen`'s `SecureScreenEffect` gating, same pattern the deleted
     *  `VaultPlayerScreen` used directly. `PlayerUiState` doesn't carry a
     *  [ContentSource] the way `ReaderUiState` does (see [item]'s synthetic
     *  [LibraryItem.filePath] instead), so this is tracked explicitly rather
     *  than derived. */
    val isVaultItem: Boolean = false,
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getItem: GetLibraryItemUseCase,
    private val getProgress: GetListeningProgressUseCase,
    private val saveProgress: SaveListeningProgressUseCase,
    private val observeBookmarks: ObserveBookmarksUseCase,
    private val addBookmark: AddBookmarkUseCase,
    private val deleteBookmark: DeleteBookmarkUseCase,
    private val updateBookmarkNote: UpdateBookmarkNoteUseCase,
    private val controllerFuture: ListenableFuture<MediaController>,
    private val chapterExtractor: ChapterExtractor,
    private val sleepTimer: SleepTimer,
    private val logger: LibravaultLogger,
    private val playbackStateHolder: PlaybackStateHolder,
    // #493 — resolves a ContentSource.VaultEntry and its bookmarks, mirroring
    // ReaderViewModel's own vaultRef/sessionManager fork.
    private val sessionManager: VaultSessionManager,
    // Phase 3 (#508) — read-once-per-MediaItem-build access to
    // VaultNotificationMetadataPreference in buildMediaItem, same plain-reader
    // shape SkipDurationPreference already uses elsewhere in this module.
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    companion object {
        private const val TAG            = "PlayerViewModel"
        private const val SKIP_MS        = 30_000L   // 30-second skip
        private const val PROGRESS_SAVE_INTERVAL_MS = 5_000L
        private const val MAX_RETRIES    = 4
        private const val RETRY_DELAY_MS = 500L
    }

    private val itemId: Long? = savedStateHandle.get<Long>("itemId")?.takeIf { it > 0 }
    private val initialSeekMs: Long? = savedStateHandle.get<Long>("seekMs")?.takeIf { it >= 0 }

    // vaultId/fileId come from navigation back-stack for a Screen.VaultPlay entry
    // (#493) — mutually exclusive with itemId, same fork ReaderViewModel already
    // uses for Screen.VaultRead. Resolved once, synchronously, from nav args so
    // the bookmarks StateFlow below — declared before init{} runs — knows at
    // construction time which backing source to use.
    private val vaultRef: Pair<String, String>? =
        savedStateHandle.get<String>("vaultId")?.let { vaultId ->
            savedStateHandle.get<String>("fileId")?.let { fileIdHex -> vaultId to fileIdHex }
        }

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // #493 — a vault session's bookmarks live in the encrypted manifest, not Room,
    // and don't change from outside this ViewModel — unlike the Room path, there's
    // no Flow to observe. Seeded once in loadItem() and mutated locally by
    // add/removeBookmark below, mirroring ReaderViewModel's _vaultBookmarks pattern.
    private val _vaultBookmarks = MutableStateFlow<List<Bookmark>>(emptyList())

    val bookmarks: StateFlow<List<Bookmark>> = if (vaultRef != null) {
        _vaultBookmarks.asStateFlow()
    } else {
        (itemId?.let { observeBookmarks(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    private var controller: MediaController? = null
    private var positionPollingJob: Job?     = null
    private var progressSaveJob: Job?        = null
    private var playedItemUri: String?       = null
    private var retryJob: Job?               = null
    private var lastPolledPositionMs: Long   = 0L

    /**
     * Monotonically-increasing generation counter incremented on every [play] call.
     * [onIsPlayingChanged] ignores stale `false` events whose generation doesn't
     * match, so a `false` from ExoPlayer's internal state machine during a media
     * transition can't overwrite the optimistic `isPlaying = true` set by [play].
     */
    private var playGeneration: Long = 0

    /**
     * True once [attachOrPlay] has run for this ViewModel instance. See
     * [attachOrPlay]'s doc — guards against [loadItem] and [connectWithRetry]
     * each independently trying to bootstrap playback for the same item
     * (#642), which used to double [playGeneration] and let a genuine
     * playback failure get silently swallowed as a "stale" event.
     */
    private var hasAttachedOrPlayed = false

    // ── Player listener ───────────────────────────────────────────────────────
    //
    // Declared here, *before* init{} below, deliberately — not just for proximity
    // to playGeneration. init{} calls connectController(), which can invoke this
    // listener synchronously (see connectWithRetry()'s KDoc): a `val` declared
    // textually after init{} would still be null at that point (Kotlin/JVM runs
    // property initializers in declaration order), and controller.addListener(null)
    // throws "listener must not be null" — a real, logged, on-device crash (#642)
    // that connectWithRetry()'s own retry silently papered over on every affected
    // Player-screen open.

    private val playerListener = object : Player.Listener {
        private var myGeneration: Long = 0L

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Only accept false events that match the current play generation.
            // During media transitions, ExoPlayer fires onIsPlayingChanged(false)
            // after setMediaItem(), which would overwrite the optimistic
            // isPlaying=true set by play(). The generation counter ensures
            // stale false events are ignored.
            if (!isPlaying && myGeneration < playGeneration) {
                val staleGeneration = myGeneration
                myGeneration = playGeneration
                logger.d(TAG, "Ignoring stale onIsPlayingChanged(false) — gen $staleGeneration < current $playGeneration")
                return
            }
            _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
            // Mirror to PlaybackStateHolder so the mini-player icon updates immediately
            // even when the polling loop is stopped (e.g. after pause).
            syncPlaybackStateHolder(isPlaying = isPlaying)
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

    /** Result of resolving [itemId]/[vaultRef] to a playable item — factored out of
     *  [loadItem] so the two mutually-exclusive sources (Room vs. Encrypted Vault,
     *  #493) share the one "reattach or play" tail below instead of duplicating it. */
    private data class LoadedItem(val item: LibraryItem, val startPositionMs: Long, val savedSpeed: Float)

    private fun loadItem() {
        viewModelScope.launch {
            val loaded = when {
                itemId != null -> loadRealItem(itemId)
                vaultRef != null -> loadVaultItem(vaultRef)
                else -> {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    null
                }
            } ?: return@launch
            val (item, startPositionMs, savedSpeed) = loaded

            logger.i(TAG, "Loaded: ${item.title} — resume at ${startPositionMs}ms, speed ${savedSpeed}x")
            // If the controller was already connected before loadItem() finished,
            // connectController's play() attempt was a no-op (item was null). Trigger it now.
            // If the controller already has this URI loaded (re-open case), reattach without
            // calling setMediaItem() — that resets ExoPlayer's decode buffer and causes stutter.
            controller?.let { ctrl -> attachOrPlay(ctrl, item, startPositionMs, savedSpeed) }
        }
    }

    /**
     * Bootstraps playback for [item] once both the loaded item and a connected
     * [MediaController] are available. [loadItem] and [connectWithRetry] each
     * independently reach the point where they'd want to do this — whichever
     * completes second is the one that actually needs to — so this is guarded
     * by [hasAttachedOrPlayed] to run at most once per ViewModel instance.
     *
     * That guard is load-bearing, not defensive-for-its-own-sake (#642): without
     * it, both call sites really could each run this once for the same item —
     * `connectWithRetry`'s own retry (itself forced by the construction-order
     * bug [playerListener]'s KDoc describes) reliably widened the window for
     * `loadItem`'s coroutine to also be ready at the same time. Firing this
     * twice doesn't just redundantly re-seek/re-play — it bumps [playGeneration]
     * an extra time, which doubles how many `onIsPlayingChanged(false)` events
     * the generation guard above treats as "stale." That's exactly the
     * mechanism that let a genuine playback failure get silently swallowed,
     * leaving the UI stuck showing "playing" with an unresponsive play/pause
     * button — the reported symptom.
     *
     * Internal rather than private so [PlayerViewModelTest] can call it a
     * second time directly and assert [hasAttachedOrPlayed]'s guard actually
     * holds — the real double-trigger race (loadItem's coroutine and
     * connectWithRetry's callback racing on real dispatchers) isn't
     * reproducible through this module's synchronous test harness, matching
     * the "mark pure-enough helpers internal for direct testability"
     * convention [xyz.libravault.core.logger.LibravaultLogger.write] already
     * documents for the same reason.
     */
    internal fun attachOrPlay(ctrl: MediaController, item: LibraryItem, startPositionMs: Long, startSpeed: Float) {
        if (hasAttachedOrPlayed) return
        hasAttachedOrPlayed = true
        val uri = android.net.Uri.parse(item.filePath)
        if (ctrl.currentMediaItem?.localConfiguration?.uri == uri) {
            val holder = playbackStateHolder.state.value
            val livePos = if (holder.itemId == item.id) holder.lastKnownPositionMs else null
            val resumePos = initialSeekMs ?: livePos ?: startPositionMs
            if (initialSeekMs != null || ctrl.currentPosition < resumePos) {
                ctrl.seekTo(resumePos)
            }
            ctrl.setPlaybackSpeed(startSpeed)
            if (!ctrl.isPlaying) ctrl.play()
            playedItemUri = uri.toString()
            playGeneration++
            _uiState.value = _uiState.value.copy(
                isPlaying  = true,
                positionMs = ctrl.currentPosition,
            )
            syncPlaybackStateHolder(isPlaying = true)
            startPolling()
            startProgressSaving()
            updateChapters()
        } else {
            play(uri, startPositionMs = startPositionMs, startSpeed = startSpeed)
        }
    }

    private suspend fun loadRealItem(id: Long): LoadedItem? {
        val item = getItem(id)
        if (item == null) {
            _uiState.value = _uiState.value.copy(isLoading = false, error = "Item not found.")
            return null
        }
        val progress = getProgress(id)
        val startPositionMs = initialSeekMs ?: progress?.positionMs ?: 0L
        val savedSpeed = progress?.playbackSpeed ?: 1.0f
        _uiState.value = _uiState.value.copy(
            item                 = item,
            isLoading            = false,
            savedStartPositionMs = startPositionMs,
            playbackSpeed        = savedSpeed,
        )
        return LoadedItem(item, startPositionMs, savedSpeed)
    }

    /**
     * Encrypted Vault flow (#493) — resolve a [ContentSource.VaultEntry] and seed
     * its bookmarks, mirroring [xyz.libravault.feature.reader.ReaderViewModel]'s
     * own `vaultRef != null` branch. The synthetic [LibraryItem.filePath] carries
     * a `vault://$vaultId/$fileIdHex` URI (see [VaultAwareMediaSourceFactory][
     * xyz.libravault.feature.player.service.VaultAwareMediaSourceFactory]) — every
     * other code path in this ViewModel (play/reattach/polling) already just
     * threads [LibraryItem.filePath] through to [MediaController]/[MediaItem]
     * unmodified, so no separate vault branch is needed anywhere else.
     *
     * No persisted resume position for vault audio (matches the deleted
     * `VaultPlayerViewModel`'s existing behavior — always starts at 0:00) and no
     * chapters (see [updateChapters]'s early return) — both explicit v1 scope
     * decisions, not regressions.
     */
    private suspend fun loadVaultItem(vault: Pair<String, String>): LoadedItem? {
        val (vaultId, fileIdHex) = vault
        return try {
            if (!sessionManager.isUnlocked(vaultId)) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "Vault is locked")
                return null
            }
            val store = sessionManager.requireUnlocked(vaultId)
            val fileId = fileIdHex.hexToFileId()
            val entry = store.listEntries().find { it.fileId.contentEquals(fileId) }
            if (entry == null) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "File not found in this vault")
                return null
            }
            if (entry.format !in VAULT_AUDIO_FORMAT_NAMES) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error     = "This isn't an audio file — open it from the reader instead",
                )
                return null
            }
            val format = MediaFormat.entries.find { it.name == entry.format }
            if (format == null) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "Unsupported format: ${entry.format}")
                return null
            }
            _vaultBookmarks.value = entry.bookmarks.map { it.toDomainBookmark() }
            val item = LibraryItem(
                id            = VAULT_TRANSIENT_ITEM_ID,
                vaultFolderId = 0L,
                filePath      = "$VAULT_MEDIA_URI_SCHEME://$vaultId/$fileIdHex",
                title         = entry.title,
                author        = entry.author ?: "",
                format        = format,
            )
            _uiState.value = _uiState.value.copy(
                item                 = item,
                isLoading            = false,
                savedStartPositionMs = 0L,
                playbackSpeed        = 1.0f,
                isVaultItem          = true,
            )
            LoadedItem(item, startPositionMs = 0L, savedSpeed = 1.0f)
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(isLoading = false, error = "Could not open vault file: ${e.message}")
            null
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
                // Item may have loaded before the controller was ready — play now.
                // Check if the controller already has this item loaded (same URI) to avoid
                // reinitializing ExoPlayer's media pipeline, which causes audio stutter.
                //
                // #493 bug found during vault testing: this used to clear any existing
                // error unconditionally, before checking whether an item even loaded —
                // a successful MediaController (re)connection would silently wipe out a
                // legitimate vault-resolution error ("Vault is locked" / "File not found
                // in this vault" / not-an-audio-file) that loadItem() had just set,
                // since connectController() always runs right after loadItem() in init{}
                // regardless of whether loadItem() succeeded. Only clear the error once
                // there's actually an item to play — same rationale as the retry-recovery
                // UX this line originally existed for, just gated correctly now.
                val item = _uiState.value.item ?: return@addListener
                if (_uiState.value.error != null) {
                    _uiState.value = _uiState.value.copy(error = null)
                }
                attachOrPlay(ctrl, item, _uiState.value.savedStartPositionMs, _uiState.value.playbackSpeed)
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

    // ── Playback controls ─────────────────────────────────────────────────────

    fun play(uri: Uri, startPositionMs: Long = 0L, startSpeed: Float = 1.0f) {
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
        // Bump the generation counter BEFORE setting the media item so the
        // listener's myGeneration guard catches any stale false events that
        // ExoPlayer fires during the internal state machine transition.
        playGeneration++
        val mediaItem = buildMediaItem(uri)
        ctrl.setMediaItem(mediaItem, startPositionMs)
        // Apply saved speed before prepare so playback starts at the correct speed
        ctrl.setPlaybackSpeed(startSpeed)
        ctrl.prepare()
        ctrl.play()
        // Update PlaybackStateHolder immediately so the mini-player shows the new
        // item without waiting for the next polling cycle (200ms delay).
        // The polling loop in startPolling() keeps it up to date thereafter.
        syncPlaybackStateHolder(isPlaying = true)
        // Optimistically set isPlaying = true so the PlayerScreen UI reflects
        // playing state immediately, even before onIsPlayingChanged fires.
        // This fixes LIB-190 where the player screen shows paused UI while
        // audio is actually playing (onIsPlayingChanged not triggered because
        // isPlaying state didn't change).
        _uiState.value = _uiState.value.copy(isPlaying = true)
        startProgressSaving()
        updateChapters()
    }

    /**
     * #493 QA finding: no [MediaMetadata] mechanism exists anywhere in this codebase
     * today (every real-file `MediaItem` is bare `MediaItem.fromUri(uri)`, relying
     * entirely on the audio file's own embedded tags for the system notification's
     * title/artist) — so leaving a vault `MediaItem` equally bare would let
     * [VaultAwareMediaSourceFactory][xyz.libravault.feature.player.service.VaultAwareMediaSourceFactory]'s
     * `VaultDataSource` stream the file's own embedded ID3/tag bytes straight into
     * Media3's metadata extractor, printing the real title/author on the lock screen
     * regardless of any future "generic placeholder" setting (PRD §8). Real-file
     * behavior is left untouched (avoids an unrelated regression risk on the far
     * larger non-vault user base) — only a vault `MediaItem` gets explicit
     * [MediaMetadata] attached, which Media3 always prefers over an extracted tag.
     * Phase 3 (#508) wires the actual placeholder-vs-real toggle
     * ([VaultNotificationMetadataPreference]): real title/author when enabled,
     * a generic "Vault" title with no artist when disabled (the new default —
     * see that object's doc for why this polarity, unlike Screen Security,
     * defaults to the more private option rather than to today's shipped
     * behavior).
     */
    private fun buildMediaItem(uri: Uri): MediaItem {
        if (uri.scheme != VAULT_MEDIA_URI_SCHEME) return MediaItem.fromUri(uri)
        val item = _uiState.value.item
        val metadata = if (VaultNotificationMetadataPreference.isEnabled(appContext)) {
            MediaMetadata.Builder()
                .setTitle(item?.title)
                .setArtist(item?.author)
                .build()
        } else {
            MediaMetadata.Builder()
                .setTitle(VAULT_PLACEHOLDER_TITLE)
                .setArtist(null)
                .build()
        }
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }

    fun togglePlayPause() {
        val ctrl = controller ?: return
        val wasPlaying = ctrl.isPlaying
        if (wasPlaying) ctrl.pause() else ctrl.play()
        // Optimistic update: reflect the new state immediately instead of waiting for
        // onIsPlayingChanged, which can be dropped by the generation guard on first use.
        _uiState.value = _uiState.value.copy(isPlaying = !wasPlaying)
        syncPlaybackStateHolder(isPlaying = !wasPlaying)
    }

    /**
     * Uses [SeekClamp.clamp] rather than a naive `.coerceAtMost(ctrl.duration)` —
     * found via a merge conflict with #573, which fixed the identical bug
     * independently in the now-deleted `VaultPlayerViewModel` (duration is
     * `C.TIME_UNSET`, a large negative sentinel, while still buffering; clamping
     * against it directly forces the seek target hugely negative instead of
     * treating "unknown duration" as "no upper bound"). [SeekClamp] already
     * existed and was already used by `LibraryViewModel`/`ReaderViewModel`'s own
     * ±seek controls — this ViewModel's `skipForward` was the one holdout still
     * doing the clamp inline, so this fixes a real, pre-existing (not #493-
     * introduced) latent bug in the audiobook player screen's own seek buttons,
     * not just vault audio.
     */
    fun skipForward() {
        val ctrl = controller ?: return
        ctrl.seekTo(SeekClamp.clamp(ctrl.currentPosition, SKIP_MS, ctrl.duration))
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
        // Optimistically set isPlaying = true; onIsPlayingChanged may not fire
        // if isPlaying didn't change (player already playing before error).
        _uiState.value = _uiState.value.copy(isPlaying = true)
    }

    fun skipBack() {
        val ctrl = controller ?: return
        ctrl.seekTo(SeekClamp.clamp(ctrl.currentPosition, -SKIP_MS, ctrl.duration))
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        // Immediately reflect the new position so the seek bar doesn't snap back
        // while paused (the polling loop is stopped when not playing).
        _uiState.value = _uiState.value.copy(positionMs = positionMs)
    }

    // ── Speed ─────────────────────────────────────────────────────────────────

    /** Valid range 0.5× to 3.0× in 0.25 steps. Persists speed to DB immediately. */
    fun setSpeed(speed: Float) {
        val snapped = snapPlaybackSpeed(speed)
        controller?.setPlaybackSpeed(snapped)
        // Persist speed immediately so the per-book preference survives
        // process death. Also update the UI state so the SpeedPickerSheet
        // and speed display reflect the new value right away.
        _uiState.value = _uiState.value.copy(playbackSpeed = snapped)
        val id = itemId ?: return
        viewModelScope.launch {
            val current = getProgress(id)
            saveProgress(
                ListeningProgress(
                    itemId         = id,
                    positionMs     = current?.positionMs ?: 0L,
                    chapterIndex   = current?.chapterIndex ?: 0,
                    lastListenedAt = Instant.now(),
                    playbackSpeed  = snapped,
                )
            )
        }
    }

    // ── Chapters ──────────────────────────────────────────────────────────────

    private fun updateChapters() {
        // No chapter extraction for vault audio (#493) — matches the deleted
        // VaultPlayerScreen's existing hardcoded no-chapters state. ChapterExtractor
        // expects a real content://file:// URI it can open directly, not a vault://
        // one, and there's no chapter concept for vault items today regardless.
        if (vaultRef != null) return
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
        val vault = vaultRef
        launchOrNoticeLock {
            if (vault != null) {
                val (vaultId, fileIdHex) = vault
                val vb = sessionManager.requireUnlocked(vaultId)
                    .addBookmark(fileIdHex.hexToFileId(), "ms:$positionMs", label ?: formatPosition(positionMs))
                _vaultBookmarks.update { it + vb.toDomainBookmark() }
                _uiState.value = _uiState.value.copy(lastAddedBookmarkId = vb.id)
                logger.i(TAG, "Vault bookmark added at ${formatPosition(positionMs)} (id=${vb.id})")
            } else {
                val id = itemId ?: return@launchOrNoticeLock
                val newId = addBookmark(
                    Bookmark(
                        itemId      = id,
                        positionRef = "ms:$positionMs",
                        label       = label ?: formatPosition(positionMs),
                    )
                )
                _uiState.value = _uiState.value.copy(lastAddedBookmarkId = newId)
                logger.i(TAG, "Bookmark added at ${formatPosition(positionMs)} (id=$newId)")
            }
        }
    }

    fun clearBookmarkToast() {
        if (_uiState.value.lastAddedBookmarkId != null) {
            _uiState.value = _uiState.value.copy(lastAddedBookmarkId = null)
        }
    }

    fun seekToBookmark(bookmark: Bookmark) {
        val ms = bookmark.positionRef.removePrefix("ms:").toLongOrNull() ?: return
        controller?.seekTo(ms)
        controller?.play()
        _uiState.value = _uiState.value.copy(positionMs = ms, isPlaying = true)
        startPolling()
        hideBookmarks()
    }

    fun removeBookmark(id: Long) {
        val vault = vaultRef
        launchOrNoticeLock {
            if (vault != null) {
                val (vaultId, fileIdHex) = vault
                sessionManager.requireUnlocked(vaultId).removeBookmark(fileIdHex.hexToFileId(), id)
                _vaultBookmarks.update { list -> list.filterNot { it.id == id } }
            } else {
                deleteBookmark(id)
            }
        }
    }

    fun updateBookmarkNote(id: Long, note: String?) {
        val vault = vaultRef
        launchOrNoticeLock {
            if (vault != null) {
                val (vaultId, fileIdHex) = vault
                sessionManager.requireUnlocked(vaultId).updateBookmarkNote(fileIdHex.hexToFileId(), id, note)
                _vaultBookmarks.update { list -> list.map { if (it.id == id) it.copy(note = note) else it } }
            } else {
                updateBookmarkNote.invoke(id, note)
            }
        }
    }

    /** Runs [block] in [viewModelScope], treating [VaultLockedException] as "the
     *  vault locked mid-operation" rather than an unhandled crash — same pattern
     *  [xyz.libravault.feature.reader.ReaderViewModel.launchOrNoticeLock] and the
     *  deleted `VaultPlayerViewModel` use (#526). A plain `viewModelScope.launch`
     *  for the non-vault path, since [VaultLockedException] can never be thrown
     *  there — used unconditionally purely so both branches share one call shape. */
    private fun launchOrNoticeLock(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: VaultLockedException) {
                _uiState.value = _uiState.value.copy(wasLocked = true)
            }
        }
    }

    /**
     * Called from the screen's `ON_RESUME` observer (same
     * `DisposableEffect`+`LifecycleEventObserver` idiom `ReaderScreen`/the deleted
     * `VaultPlayerScreen` already use) — #526: a vault-sourced session otherwise
     * never re-checks lock state after [loadItem]. Flips [PlayerUiState.wasLocked]
     * if [sessionManager] no longer reports this vault unlocked. A no-op for a
     * non-vault [vaultRef].
     */
    fun checkStillUnlocked() {
        val vault = vaultRef ?: return
        if (!_uiState.value.isLoading && !sessionManager.isUnlocked(vault.first)) {
            _uiState.value = _uiState.value.copy(wasLocked = true)
        }
    }

    /** [PlaybackStateHolder.update]/[PlaybackStateHolder.updateVault]'s single
     *  call site — branches on [vaultRef] so the ~5 previous inline call sites
     *  (loadItem's reattach, connectWithRetry's reattach, play, togglePlayPause,
     *  the polling loop, onIsPlayingChanged) can't drift out of sync on which of
     *  the two holder methods a given code path should use. Critically, this
     *  keeps [PlaybackStateHolder.State.itemId] null for a vault item — see
     *  [PlaybackStateHolder.State.vaultEntry]'s doc for why
     *  [xyz.libravault.feature.player.service.LibravaultMediaCallback]'s guard
     *  depends on that. */
    private fun syncPlaybackStateHolder(isPlaying: Boolean) {
        val item = _uiState.value.item ?: return
        val vault = vaultRef
        if (vault != null) {
            val (vaultId, fileIdHex) = vault
            playbackStateHolder.updateVault(
                vaultEntry   = ContentSource.VaultEntry(vaultId, fileIdHex, item.format),
                title        = item.title,
                author       = item.author,
                coverArtPath = item.coverArtPath,
                isPlaying    = isPlaying,
            )
        } else {
            playbackStateHolder.update(
                itemId        = item.id,
                vaultFolderId = item.vaultFolderId,
                filePath      = item.filePath,
                title         = item.title,
                author        = item.author,
                coverArtPath  = item.coverArtPath,
                isPlaying     = isPlaying,
            )
        }
    }

    // ── Position polling & progress saving ────────────────────────────────────

    private fun startPolling() {
        positionPollingJob?.cancel()
        positionPollingJob = viewModelScope.launch {
            while (isActive) {
                val ctrl = controller ?: break
                val pos  = ctrl.currentPosition
                lastPolledPositionMs = pos
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
                syncPlaybackStateHolder(isPlaying = ctrl.isPlaying)
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
                val speed   = _uiState.value.playbackSpeed
                val id      = itemId ?: continue
                saveProgress(
                    ListeningProgress(
                        itemId         = id,
                        positionMs     = pos,
                        chapterIndex   = chapIdx,
                        lastListenedAt = Instant.now(),
                        playbackSpeed  = speed,
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
        // lastPolledPositionMs is updated every 200 ms by the polling loop and is more
        // reliable than controller?.currentPosition, which can be 0 if the controller
        // is mid-transition when onCleared() fires.
        val pos = lastPolledPositionMs.takeIf { it > 0L } ?: controller?.currentPosition
        val id = itemId
        // Synchronous StateFlow write — new VM reads this in connectWithRetry() before
        // the Room write below has committed.
        if (pos != null) playbackStateHolder.updatePosition(pos)
        if (pos != null && id != null) {
            val chapterIndex = _uiState.value.currentChapterIndex
            val speed        = _uiState.value.playbackSpeed
            // viewModelScope is cancelled before onCleared() runs in AndroidX lifecycle 2.x,
            // so we use a fresh scope that is not tied to the ViewModel.
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                saveProgress(
                    ListeningProgress(
                        itemId         = id,
                        positionMs     = pos,
                        chapterIndex   = chapterIndex,
                        lastListenedAt = Instant.now(),
                        playbackSpeed  = speed,
                    )
                )
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

/**
 * A vault bookmark's [id]/[positionRef]/[label]/[note] map straight across to
 * [Bookmark]; [itemId] gets the same `-1L` transient-item sentinel
 * [xyz.libravault.core.storage.usecase.OpenFileUseCase] uses for external-intent
 * items with no Room row and [xyz.libravault.feature.reader.ReaderViewModel]'s
 * own `VAULT_TRANSIENT_ITEM_ID` uses for vault bookmarks/highlights there — a
 * vault-sourced audio bookmark is equally not Room-backed. Duplicated per module
 * rather than a new cross-module dependency, matching that precedent.
 */
private const val VAULT_TRANSIENT_ITEM_ID = -1L

/** Generic lock-screen/notification title for a vault item when
 * [VaultNotificationMetadataPreference] is disabled (the default) — see
 * [PlayerViewModel.buildMediaItem]. */
private const val VAULT_PLACEHOLDER_TITLE = "Vault"

private fun VaultBookmark.toDomainBookmark(): Bookmark = Bookmark(
    id          = id,
    itemId      = VAULT_TRANSIENT_ITEM_ID,
    positionRef = positionRef,
    label       = label,
    note        = note,
    createdAt   = Instant.ofEpochMilli(createdAtEpochMillis),
)
