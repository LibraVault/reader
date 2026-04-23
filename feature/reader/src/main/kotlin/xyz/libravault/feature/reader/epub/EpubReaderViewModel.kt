package xyz.libravault.feature.reader.epub

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Publication
import xyz.libravault.core.logger.LibravaultLogger
import javax.inject.Inject

/**
 * ViewModel scoped to the EPUB reader sub-screen.
 *
 * Responsibilities:
 *  - Open a [Publication] via [ReadiumProvider] and expose it as a [StateFlow].
 *  - Close the [Publication] when the ViewModel is cleared (i.e. when the user
 *    navigates away from the reader), freeing native parser resources.
 *
 * Deliberately separate from [xyz.libravault.feature.reader.ReaderViewModel] so
 * that the parent ViewModel remains Android-free and easily unit-testable. The
 * parent handles all persistence (progress, bookmarks, highlights); this one
 * only manages the Readium lifecycle.
 */
@HiltViewModel
class EpubReaderViewModel @Inject constructor(
    private val readiumProvider: ReadiumProvider,
    private val logger: LibravaultLogger,
) : ViewModel() {

    private val _state = MutableStateFlow<EpubPublicationState>(EpubPublicationState.Idle)
    val state: StateFlow<EpubPublicationState> = _state.asStateFlow()

    /**
     * Opens the EPUB at [uri]. Idempotent — if a publication is already open
     * for the same URI, does nothing. If a different URI is passed (e.g. on
     * re-use of the ViewModel), the old publication is closed first.
     */
    fun openPublication(uri: Uri) {
        val current = _state.value
        if (current is EpubPublicationState.Ready && current.uri == uri) return

        // Close any previously open publication before opening a new one
        if (current is EpubPublicationState.Ready) {
            current.publication.close()
        }

        _state.value = EpubPublicationState.Loading

        viewModelScope.launch {
            readiumProvider.open(uri)
                .onSuccess { publication ->
                    logger.i(TAG, "Opened publication: ${publication.metadata.title}")
                    _state.value = EpubPublicationState.Ready(uri, publication)
                }
                .onFailure { error ->
                    logger.e(TAG, "Failed to open publication at $uri", error)
                    _state.value = EpubPublicationState.Error(
                        error.message ?: "Unknown error opening publication"
                    )
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Free native parser resources when the user navigates away
        val current = _state.value
        if (current is EpubPublicationState.Ready) {
            current.publication.close()
            logger.i(TAG, "Publication closed on ViewModel clear")
        }
    }

    private companion object {
        const val TAG = "EpubReaderViewModel"
    }
}

// ── State ──────────────────────────────────────────────────────────────────────

sealed class EpubPublicationState {

    /** Initial state before [EpubReaderViewModel.openPublication] is called. */
    data object Idle : EpubPublicationState()

    /** Publication is being retrieved and parsed. */
    data object Loading : EpubPublicationState()

    /** Publication is open and ready to display. */
    data class Ready(
        val uri: Uri,
        val publication: Publication,
    ) : EpubPublicationState()

    /** Non-recoverable error opening the publication. */
    data class Error(val message: String) : EpubPublicationState()
}
