package xyz.libravault.feature.player.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton that tracks whether a media item is actively loaded in the player.
 *
 * The [PlayerViewModel] updates this during its position polling loop so the
 * [LibraryScreen] can show a mini-player bar when content is playing.
 *
 * Uses the existing [MediaController] singleton internally — this is just a
 * lightweight state relay so the library module doesn't need a direct dependency
 * on Media3.
 */
@Singleton
class PlaybackStateHolder @Inject constructor() {

    data class State(
        val itemId: Long? = null,
        val title: String = "",
        val author: String = "",
        val coverArtPath: String? = null,
        val isPlaying: Boolean = false,
        val isActive: Boolean = false,       // true if any item has been loaded
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun update(
        itemId: Long?,
        title: String,
        author: String,
        coverArtPath: String?,
        isPlaying: Boolean,
    ) {
        _state.value = State(
            itemId = itemId,
            title = title,
            author = author,
            coverArtPath = coverArtPath,
            isPlaying = isPlaying,
            isActive = true,
        )
    }

    fun clear() {
        _state.value = State()
    }
}
