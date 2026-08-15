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
import xyz.libravault.core.vaultcrypto.VaultFileReader
import xyz.libravault.core.vaultstore.VaultSessionManager
import javax.inject.Inject

private const val SKIP_MS = 30_000L

data class VaultPlayerUiState(
    val title: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
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
 * `VaultDataSource.Factory { reader }` fed straight into
 * [ProgressiveMediaSource] is exactly the pattern
 * `core:vaultcontent`'s `VaultDataSource` doc comment names as the
 * no-URI-scheme-needed path for a caller that already holds an open
 * [VaultFileReader].
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

    private var reader: VaultFileReader? = null
    private var player: ExoPlayer? = null

    private val _uiState = MutableStateFlow(VaultPlayerUiState())
    val uiState: StateFlow<VaultPlayerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            if (!sessionManager.isUnlocked(vaultId)) {
                _uiState.update { it.copy(isLoading = false, error = "Vault is locked") }
                return@launch
            }
            val store = sessionManager.requireUnlocked(vaultId)
            val entry = store.listEntries().find { it.fileId.contentEquals(fileId) }
            if (entry == null) {
                _uiState.update { it.copy(isLoading = false, error = "File not found in this vault") }
                return@launch
            }

            val r = store.openReader(fileId)
            reader = r
            val exo = ExoPlayer.Builder(context).build()
            player = exo

            val mediaSource = ProgressiveMediaSource.Factory(VaultDataSource.Factory { r })
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

    override fun onCleared() {
        player?.release()
        reader?.close()
    }
}
