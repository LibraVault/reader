@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package xyz.libravault.feature.vault

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.libravault.core.vaultcontent.VaultDataSource
import xyz.libravault.core.vaultstore.VaultBookmark
import xyz.libravault.core.vaultstore.VaultLockedException
import xyz.libravault.core.vaultstore.VaultSessionManager
import xyz.libravault.core.vaultstore.VaultStore
import xyz.libravault.core.vaultstore.hexToFileId
import javax.inject.Inject

private const val SKIP_MS = 30_000L

/**
 * One [VaultStore.openReader] call per `createDataSource()`, never a reader
 * shared across instances — see class doc above and issue #527. Extracted so
 * the fan-out itself is unit-testable without constructing a real ExoPlayer.
 */
internal fun vaultPlayerDataSourceFactory(store: VaultStore, fileId: ByteArray): VaultDataSource.Factory =
    VaultDataSource.Factory { store.openReader(fileId) }

data class VaultPlayerUiState(
    val title: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    /** Flips true once this screen detects the vault got locked out from
     * under it (#526) — same `wasLocked` pattern [VaultContentsUiState]
     * already uses. The screen pops back to the unlock flow when this
     * flips. */
    val wasLocked: Boolean = false,
)

/**
 * Plays one vault audio file with a **local, screen-scoped ExoPlayer**, not
 * `feature:player`'s shared singleton (`PlayerModule.provideExoPlayer`) —
 * that instance is wired to `PlaybackService`/`MediaSession`/lockscreen
 * controls keyed off a Room `itemId`, which vault content doesn't have.
 * Concretely: **no background playback** — leaving this screen releases the
 * player and stops audio, unlike the main library's player. Explicitly
 * scoped out for this pass; see the PR description.
 *
 * [vaultPlayerDataSourceFactory]'s `VaultDataSource.Factory { store.openReader(fileId) }`
 * fed straight into [ProgressiveMediaSource] is exactly the pattern
 * `core:vaultcontent`'s `VaultDataSource` doc comment names as the
 * no-URI-scheme-needed path for a caller that can open a fresh reader per
 * factory invocation — one fresh reader per `createDataSource()` call, since
 * Media3 may open/close a track's `DataSource` more than once (retries,
 * re-buffering) and neither a reader nor a `VaultDataSource` is safe to share
 * across instances.
 */
@HiltViewModel
class VaultPlayerViewModel @Inject constructor(
    private val sessionManager: VaultSessionManager,
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val vaultId: String = checkNotNull(savedStateHandle["vaultId"]) { "VaultPlayerScreen requires a vaultId nav argument" }
    private val fileIdHex: String = checkNotNull(savedStateHandle["fileId"]) { "VaultPlayerScreen requires a fileId nav argument" }
    private val fileId: ByteArray = fileIdHex.hexToFileId()

    private var player: ExoPlayer? = null
    private var store: VaultStore? = null

    private val _uiState = MutableStateFlow(VaultPlayerUiState())
    val uiState: StateFlow<VaultPlayerUiState> = _uiState.asStateFlow()

    private val _bookmarks = MutableStateFlow<List<VaultBookmark>>(emptyList())
    val bookmarks: StateFlow<List<VaultBookmark>> = _bookmarks.asStateFlow()

    init {
        viewModelScope.launch {
            if (!sessionManager.isUnlocked(vaultId)) {
                _uiState.update { it.copy(isLoading = false, error = "Vault is locked") }
                return@launch
            }
            val s = sessionManager.requireUnlocked(vaultId)
            store = s
            val entry = s.listEntries().find { it.fileId.contentEquals(fileId) }
            if (entry == null) {
                _uiState.update { it.copy(isLoading = false, error = "File not found in this vault") }
                return@launch
            }
            _bookmarks.value = entry.bookmarks

            val exo = ExoPlayer.Builder(context).build()
            player = exo

            val mediaSource = ProgressiveMediaSource.Factory(vaultPlayerDataSourceFactory(s, fileId))
                .createMediaSource(MediaItem.fromUri(Uri.parse("vault://$fileIdHex")))
            exo.setMediaSource(mediaSource)
            exo.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _uiState.update { it.copy(isPlaying = isPlaying) }
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        _uiState.update { it.copy(isLoading = false, durationMs = exo.duration.coerceAtLeast(0)) }
                    }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    _uiState.update { it.copy(isLoading = false, error = error.message ?: "Playback error") }
                }
            })
            exo.prepare()
            exo.play()
            _uiState.update { it.copy(title = entry.title) }
            startPositionTicker()
        }
    }

    private fun startPositionTicker() {
        viewModelScope.launch {
            while (isActive) {
                player?.let { exo ->
                    _uiState.update {
                        it.copy(
                            positionMs = exo.currentPosition.coerceAtLeast(0),
                            bufferedMs = exo.bufferedPosition.coerceAtLeast(0),
                            durationMs = exo.duration.coerceAtLeast(0),
                        )
                    }
                }
                delay(500)
            }
        }
    }

    fun onPlayPause() {
        val exo = player ?: return
        if (exo.isPlaying) exo.pause() else exo.play()
    }

    fun onSeek(positionMs: Long) {
        player?.seekTo(positionMs.coerceAtLeast(0))
    }

    fun onSkipBack() {
        player?.let { it.seekTo((it.currentPosition - SKIP_MS).coerceAtLeast(0)) }
    }

    fun onSkipForward() {
        player?.let { exo -> exo.seekTo((exo.currentPosition + SKIP_MS).coerceAtMost(exo.duration.coerceAtLeast(0))) }
    }

    /**
     * Called from the screen's `ON_RESUME` observer (same
     * `DisposableEffect`+`LifecycleEventObserver` idiom `VaultListScreen`
     * already uses) — #526: this screen otherwise never re-checks lock state
     * after [init]. Flips [VaultPlayerUiState.wasLocked] if [sessionManager]
     * no longer reports this vault unlocked.
     */
    fun checkStillUnlocked() {
        if (!_uiState.value.isLoading && !sessionManager.isUnlocked(vaultId)) {
            _uiState.update { it.copy(wasLocked = true) }
        }
    }

    /** Runs [block] in [viewModelScope], treating [VaultLockedException] as
     * "the vault locked mid-operation" rather than an unhandled crash (#526)
     * — the mutation itself already aborted cleanly inside [VaultStore]; this
     * just makes the screen notice and pop back instead of the exception
     * propagating unhandled out of the coroutine. */
    private fun launchOrNoticeLock(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: VaultLockedException) {
                _uiState.update { it.copy(wasLocked = true) }
            }
        }
    }

    // ── Bookmarks ─────────────────────────────────────────────────────────────

    /** Bookmarks the current playback position using the `"ms:N"` convention
     * [VaultBookmark.positionRef] shares with `core.domain.model.Bookmark` —
     * a no-op if the vault store isn't reachable yet (e.g. tapped before the
     * entry finished loading). */
    fun addBookmark(label: String? = null) {
        val s = store ?: return
        val positionMs = _uiState.value.positionMs
        launchOrNoticeLock {
            val bookmark = s.addBookmark(fileId, "ms:$positionMs", label ?: formatPosition(positionMs))
            _bookmarks.update { it + bookmark }
        }
    }

    fun removeBookmark(id: Long) {
        val s = store ?: return
        launchOrNoticeLock {
            s.removeBookmark(fileId, id)
            _bookmarks.update { list -> list.filterNot { it.id == id } }
        }
    }

    fun updateBookmarkNote(id: Long, note: String?) {
        val s = store ?: return
        launchOrNoticeLock {
            s.updateBookmarkNote(fileId, id, note)
            _bookmarks.update { list -> list.map { if (it.id == id) it.copy(note = note) else it } }
        }
    }

    fun seekToBookmark(bookmark: VaultBookmark) {
        val ms = bookmark.positionRef.removePrefix("ms:").toLongOrNull() ?: return
        player?.seekTo(ms)
        player?.play()
        _uiState.update { it.copy(positionMs = ms, isPlaying = true) }
    }

    private fun formatPosition(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    override fun onCleared() {
        player?.release()
    }
}
