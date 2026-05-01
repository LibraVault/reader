package xyz.libravault.feature.reader.epub

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Locator
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

    // Tracks the navigator's current locator so TTS can determine which spine item to read.
    private val _currentLocator = MutableStateFlow<Locator?>(null)
    val currentLocator: StateFlow<Locator?> = _currentLocator.asStateFlow()

    fun onLocatorChanged(locator: Locator) {
        _currentLocator.value = locator
    }

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

    /**
     * Reads the HTML for the current spine item and strips tags to produce plain text for TTS.
     * Returns null if no publication is open or no locator is known.
     */
    suspend fun getChapterText(): String? = withContext(Dispatchers.IO) {
        val pub = (_state.value as? EpubPublicationState.Ready)?.publication ?: return@withContext null
        val locator = _currentLocator.value ?: run {
            // No locator yet — fall back to the first spine item.
            val first = pub.readingOrder.firstOrNull() ?: return@withContext null
            Locator(href = first.url(), mediaType = first.mediaType ?: org.readium.r2.shared.util.mediatype.MediaType.XHTML)
        }
        val link = pub.readingOrder.find { it.url() == locator.href }
            ?: pub.readingOrder.firstOrNull()
            ?: return@withContext null

        val resource = pub.get(link) ?: return@withContext null
        val bytes = resource.read().getOrNull()
        resource.close()
        bytes
            ?.let { String(it, Charsets.UTF_8) }
            ?.let { stripHtml(it) }
            ?.takeIf { it.isNotBlank() }
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

        // Strips HTML tags and collapses whitespace. Good enough for TTS; avoids
        // a full HTML parser dependency in the reader module.
        fun stripHtml(html: String): String =
            html
                .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<p[^>]*>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("&nbsp;"), " ")
                .replace(Regex("&amp;"), "&")
                .replace(Regex("&lt;"), "<")
                .replace(Regex("&gt;"), ">")
                .replace(Regex("&quot;"), "\"")
                .replace(Regex("&#?\\w+;"), " ")
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
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
