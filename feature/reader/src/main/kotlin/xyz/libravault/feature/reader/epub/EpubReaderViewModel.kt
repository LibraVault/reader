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
import org.readium.r2.shared.ExperimentalReadiumApi
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

    // Serialised JSON form of the current locator — used by ReaderScreen for bookmark fallback.
    private val _currentLocatorJson = MutableStateFlow<String?>(null)
    val currentLocatorJson: StateFlow<String?> = _currentLocatorJson.asStateFlow()

    @OptIn(ExperimentalReadiumApi::class)
    fun onLocatorChanged(locator: Locator) {
        _currentLocator.value = locator
        _currentLocatorJson.value = locator.toJSON().toString()
    }

    // Pending navigation target set when the user taps a bookmark.
    private val _pendingLocator = MutableStateFlow<Locator?>(null)
    val pendingLocator: StateFlow<Locator?> = _pendingLocator.asStateFlow()

    fun goToLocatorJson(json: String) {
        val locator = runCatching {
            Locator.fromJSON(org.json.JSONObject(json))
        }.getOrNull() ?: return
        _pendingLocator.value = locator
    }

    fun clearPendingLocator() { _pendingLocator.value = null }

    // Independent cursor for TTS chapter advancement — decoupled from the visual navigator
    // position so continuous reading doesn't fight with locator updates from the navigator.
    // -1 means "not yet set"; getChapterText() initialises it from the visual locator.
    private var ttsSpineIndex: Int = -1

    /** Reset the TTS cursor, e.g. when the user stops playback. */
    fun resetTtsPosition() { ttsSpineIndex = -1 }

    /** 0-based current TTS chapter, for the Player screen's chapter display/nav (#138). */
    val ttsChapterIndex: Int get() = ttsSpineIndex.coerceAtLeast(0)

    /** Total narratable chapters (the EPUB's spine length), for the same purpose. */
    val ttsChapterCount: Int
        get() = (_state.value as? EpubPublicationState.Ready)?.publication?.readingOrder?.size ?: 0

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
                    _state.value = if (error is DrmProtectedException) {
                        EpubPublicationState.DrmProtected(error.schemeName)
                    } else {
                        EpubPublicationState.Error(
                            error.message ?: "Unknown error opening publication"
                        )
                    }
                }
        }
    }

    /**
     * Returns chapter text starting from approximately the current visual position,
     * using [Locator.Locations.progression] to estimate the character offset.
     * Falls back to full chapter text when progression is near 0 or unavailable.
     * Also anchors the TTS spine cursor so [getNextChapterText] advances from here.
     */
    suspend fun getChapterTextFromProgression(): String? {
        val pub = (_state.value as? EpubPublicationState.Ready)?.publication ?: return null
        val locator = _currentLocator.value ?: return getChapterText()
        val link = pub.readingOrder.find { it.url() == locator.href }
            ?: pub.readingOrder.firstOrNull()
            ?: return null
        ttsSpineIndex = pub.readingOrder.indexOf(link)
        val fullText = withContext(Dispatchers.IO) { fetchAndClean(pub, link) } ?: return null
        val progression = locator.locations.progression ?: return fullText
        if (progression < 0.02) return fullText
        val approxOffset = (fullText.length * progression).toInt().coerceIn(0, fullText.length - 1)
        val tail = fullText.drop(approxOffset)
        val boundary = tail.indexOfFirst { it == '.' || it == '!' || it == '?' || it == '\n' }
        val trimFrom = if (boundary >= 0) boundary + 1 else 0
        return tail.drop(trimFrom).trimStart().takeIf { it.isNotBlank() } ?: fullText
    }

    /**
     * Reads the HTML for the current spine item and strips tags to produce plain text for TTS.
     * Also initialises the TTS spine cursor so [getNextChapterText] advances from here.
     * Returns null if no publication is open or no locator is known.
     */
    suspend fun getChapterText(): String? {
        val pub = (_state.value as? EpubPublicationState.Ready)?.publication ?: return null
        val locator = _currentLocator.value ?: run {
            val first = pub.readingOrder.firstOrNull() ?: return null
            Locator(href = first.url(), mediaType = first.mediaType ?: org.readium.r2.shared.util.mediatype.MediaType.XHTML)
        }
        val link = pub.readingOrder.find { it.url() == locator.href }
            ?: pub.readingOrder.firstOrNull()
            ?: return null

        // Anchor the TTS cursor to the chapter the user is currently reading.
        ttsSpineIndex = pub.readingOrder.indexOf(link)

        return withContext(Dispatchers.IO) { fetchAndClean(pub, link) }
    }

    /**
     * Advances the TTS cursor to the next spine item and returns its plain text.
     * Returns null at the end of the book so the caller knows to stop.
     */
    suspend fun getNextChapterText(): String? {
        val pub = (_state.value as? EpubPublicationState.Ready)?.publication ?: return null
        val nextIndex = ttsSpineIndex + 1
        val link = pub.readingOrder.getOrNull(nextIndex) ?: return null
        ttsSpineIndex = nextIndex
        return withContext(Dispatchers.IO) { fetchAndClean(pub, link) }
    }

    /**
     * Moves the TTS cursor back to the previous spine item and returns its plain
     * text. Returns null (and leaves the cursor unmoved) at the start of the book,
     * mirroring [getNextChapterText]'s end-of-book contract — used by the Player
     * screen's "previous chapter" control (#138).
     */
    suspend fun getPreviousChapterText(): String? {
        val pub = (_state.value as? EpubPublicationState.Ready)?.publication ?: return null
        val prevIndex = ttsSpineIndex - 1
        val link = pub.readingOrder.getOrNull(prevIndex) ?: return null
        ttsSpineIndex = prevIndex
        return withContext(Dispatchers.IO) { fetchAndClean(pub, link) }
    }

    private suspend fun fetchAndClean(
        pub: org.readium.r2.shared.publication.Publication,
        link: org.readium.r2.shared.publication.Link,
    ): String? {
        val resource = pub.get(link) ?: return null
        val bytes = resource.read().getOrNull()
        resource.close()
        return bytes
            ?.takeIf { it.size <= Companion.MAX_CHAPTER_BYTES }
            ?.let { String(it, Charsets.UTF_8) }
            ?.let { stripHtml(it) }
            ?.let { EpubTextPreprocessor.clean(it) }
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

    internal companion object {
        const val TAG = "EpubReaderViewModel"

        // Hard cap on per-chapter HTML size. 2 MB of plain HTML is ~700k
        // words — 12+ hours of TTS at 160 wpm. A chapter that exceeds this
        // is almost certainly a ZIP-bomb-style malicious EPUB; bail out and
        // log instead of pegging the IO thread (review finding #17 / WS3.6).
        internal const val MAX_CHAPTER_BYTES = 2 * 1024 * 1024

        /**
         * Strips HTML to plain text for TTS, using Jsoup's safe cleaner.
         *
         * Replaces a regex-based stripper (review finding #10) that was
         * O(n²) on long chapters and silently mishandled `<style>` blocks
         * containing `<` inside CSS comments, CDATA sections, etc. Jsoup
         * parses the HTML once in O(n) and handles all the edge cases.
         *
         * Returns null if [html] is malformed beyond repair or exceeds
         * [MAX_CHAPTER_BYTES]; caller should surface that to the UI.
         */
        internal fun stripHtml(html: String): String? {
            if (html.isEmpty()) return ""
            if (html.length > MAX_CHAPTER_BYTES) {
                stripHtmlLog("chapter exceeds ${MAX_CHAPTER_BYTES / 1024} KB cap (${html.length} B); refusing")
                return null
            }
            return try {
                val doc = org.jsoup.Jsoup.parse(html)
                // Drop entire elements that often carry attacker-controlled
                // content. Safelist.none() in Jsoup.clean strips the tags
                // but keeps the inner text — we want to drop the text too.
                doc.select("script, style, iframe, object, embed, noscript, svg").remove()
                doc.text()
            } catch (e: Exception) {
                stripHtmlLog("parse failure (${e.javaClass.simpleName}): ${e.message}")
                null
            }
        }

        // android.util.Log is a no-op stub in JVM unit tests — wrap so the
        // call sites stay simple and the test surface stays clean.
        private fun stripHtmlLog(msg: String) {
            runCatching { android.util.Log.w(TAG, "stripHtml: $msg") }
        }
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

    /** Publication is DRM-restricted (e.g. Adobe ADEPT, LCP) — Libravault has no
     * decryption support, so it's surfaced distinctly from [Error] to show
     * DRM-specific copy instead of a raw parser error message. */
    data class DrmProtected(val schemeName: String?) : EpubPublicationState()
}
