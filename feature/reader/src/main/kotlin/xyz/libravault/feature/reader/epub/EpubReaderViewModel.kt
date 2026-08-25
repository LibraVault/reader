package xyz.libravault.feature.reader.epub

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
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.indexOfFirstWithHref
import xyz.libravault.core.domain.model.ContentSource
import xyz.libravault.core.domain.model.ReaderChapter
import xyz.libravault.core.logger.LibravaultLogger
import xyz.libravault.core.vaultstore.VaultSessionManager
import xyz.libravault.core.vaultstore.hexToFileId
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
    private val sessionManager: VaultSessionManager,
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
    // -1 means "not yet set"; getChapterText() initialises it from the visual locator. Indexes
    // into chaptersFor()'s TOC-based chapter list (#596), not the raw spine (readingOrder).
    private var ttsChapterCursor: Int = -1

    /** Reset the TTS cursor, e.g. when the user stops playback. */
    fun resetTtsPosition() { ttsChapterCursor = -1 }

    /** 0-based current TTS chapter, for the Player screen's chapter display/nav (#138). */
    val ttsChapterIndex: Int get() = ttsChapterCursor.coerceAtLeast(0)

    /** Total narratable chapters (TOC-derived, or spine-derived as a fallback — see
     * [chaptersFor]), for the same purpose. */
    val ttsChapterCount: Int
        get() = (_state.value as? EpubPublicationState.Ready)
            ?.let { chaptersFor(it.publication).size } ?: 0

    // Table of contents for the TOC sidebar (#596) — the book's real nav doc/NCX structure,
    // nested, independent of the coarser TOC-to-spine chapter collapsing chaptersFor() does
    // for Read Aloud. Populated when a publication opens successfully; empty otherwise.
    private val _tocEntries = MutableStateFlow<List<EpubTocEntry>>(emptyList())
    val tocEntries: StateFlow<List<EpubTocEntry>> = _tocEntries.asStateFlow()

    /**
     * Opens the EPUB from [source] (#505 — a real file or an Encrypted Vault
     * entry). Idempotent — if a publication is already open for the same
     * [ContentSource], does nothing. If a different one is passed (e.g. on
     * re-use of the ViewModel), the old publication is closed first.
     */
    fun openPublication(source: ContentSource) {
        val current = _state.value
        if (current is EpubPublicationState.Ready && current.source == source) return

        // Close any previously open publication before opening a new one
        if (current is EpubPublicationState.Ready) {
            current.publication.close()
        }

        _state.value = EpubPublicationState.Loading
        _tocEntries.value = emptyList()

        viewModelScope.launch {
            val result = when (source) {
                is ContentSource.RealFile -> readiumProvider.open(source.uriString)
                is ContentSource.VaultEntry -> runCatching {
                    sessionManager.requireUnlocked(source.vaultId).openReader(source.fileIdHex.hexToFileId())
                }.fold(
                    onSuccess = { reader -> readiumProvider.openVaultFile(reader, source.fileIdHex) },
                    onFailure = { Result.failure(it) },
                )
            }
            result
                .onSuccess { publication ->
                    logger.i(TAG, "Opened publication: ${publication.metadata.title}")
                    _state.value = EpubPublicationState.Ready(source, publication)
                    _tocEntries.value = buildTocEntries(publication)
                }
                .onFailure { error ->
                    logger.e(TAG, "Failed to open publication from $source", error)
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
     * Also anchors the TTS chapter cursor so [getNextChapterText] advances from here.
     */
    suspend fun getChapterTextFromProgression(): String? {
        val pub = (_state.value as? EpubPublicationState.Ready)?.publication ?: return null
        val chapters = chaptersFor(pub)
        if (chapters.isEmpty()) return null
        val locator = _currentLocator.value ?: return getChapterText()
        val spineIndex = pub.readingOrder.indexOfFirstWithHref(locator.href) ?: 0
        val chapterIndex = chapterIndexForSpineIndex(chapters, spineIndex)
        ttsChapterCursor = chapterIndex
        val fullText = chapters[chapterIndex].chapter.textProvider().takeIf { it.isNotBlank() } ?: return null
        val progression = locator.locations.progression ?: return fullText
        if (progression < 0.02) return fullText
        val approxOffset = (fullText.length * progression).toInt().coerceIn(0, fullText.length - 1)
        val tail = fullText.drop(approxOffset)
        val boundary = tail.indexOfFirst { it == '.' || it == '!' || it == '?' || it == '\n' }
        val trimFrom = if (boundary >= 0) boundary + 1 else 0
        return tail.drop(trimFrom).trimStart().takeIf { it.isNotBlank() } ?: fullText
    }

    /**
     * Reads the HTML for the chapter the user is currently reading and strips tags to
     * produce plain text for TTS. Also anchors the TTS chapter cursor so
     * [getNextChapterText] advances from here. Returns null if no publication is open
     * or it has no narratable chapters.
     */
    suspend fun getChapterText(): String? {
        val pub = (_state.value as? EpubPublicationState.Ready)?.publication ?: return null
        val chapters = chaptersFor(pub)
        if (chapters.isEmpty()) return null
        val chapterIndex = _currentLocator.value?.let { locator ->
            val spineIndex = pub.readingOrder.indexOfFirstWithHref(locator.href) ?: 0
            chapterIndexForSpineIndex(chapters, spineIndex)
        } ?: 0

        // Anchor the TTS cursor to the chapter the user is currently reading.
        ttsChapterCursor = chapterIndex

        return chapters[chapterIndex].chapter.textProvider().takeIf { it.isNotBlank() }
    }

    /**
     * Advances the TTS cursor to the next chapter and returns its plain text.
     * Returns null at the end of the book so the caller knows to stop.
     */
    suspend fun getNextChapterText(): String? {
        val pub = (_state.value as? EpubPublicationState.Ready)?.publication ?: return null
        val chapters = chaptersFor(pub)
        val nextIndex = ttsChapterCursor + 1
        val chapter = chapters.getOrNull(nextIndex) ?: return null
        ttsChapterCursor = nextIndex
        return chapter.chapter.textProvider().takeIf { it.isNotBlank() }
    }

    /**
     * Moves the TTS cursor back to the previous chapter and returns its plain
     * text. Returns null (and leaves the cursor unmoved) at the start of the book,
     * mirroring [getNextChapterText]'s end-of-book contract — used by the Player
     * screen's "previous chapter" control (#138).
     */
    suspend fun getPreviousChapterText(): String? {
        val pub = (_state.value as? EpubPublicationState.Ready)?.publication ?: return null
        val chapters = chaptersFor(pub)
        val prevIndex = ttsChapterCursor - 1
        val chapter = chapters.getOrNull(prevIndex) ?: return null
        ttsChapterCursor = prevIndex
        return chapter.chapter.textProvider().takeIf { it.isNotBlank() }
    }

    // ── TOC-based chapter model (#596) ──────────────────────────────────────────

    internal data class EpubChapter(val chapter: ReaderChapter, val spineIndex: Int)

    // Keyed by Publication identity rather than ContentSource — cheap to recompute, but
    // there's no reason to walk the TOC/spine again on every getNextChapterText() call
    // for the same open book.
    private var cachedChapters: List<EpubChapter> = emptyList()
    private var cachedChaptersPublication: Publication? = null

    private fun chaptersFor(pub: Publication): List<EpubChapter> {
        if (cachedChaptersPublication !== pub) {
            cachedChapters = buildChapters(pub)
            cachedChaptersPublication = pub
        }
        return cachedChapters
    }

    /**
     * Builds the Read Aloud chapter list from [Publication.tableOfContents] (#596),
     * snapping each TOC entry to its containing spine item — anchor-accurate splitting
     * within a single spine file is out of scope for this issue (see #596's "Out of
     * scope"). Multiple TOC entries commonly point into the same spine file (e.g.
     * sub-headings of one XHTML chapter); those collapse into a single chapter here so
     * "next chapter" doesn't re-read the same file's text more than once.
     *
     * Falls back to the original one-chapter-per-spine-file chunking (#137) when the
     * EPUB ships no usable table of contents (no nav doc/NCX, or one whose entries
     * don't resolve to any spine item) — same behaviour as before this issue.
     */
    internal fun buildChapters(pub: Publication): List<EpubChapter> {
        val order = pub.readingOrder
        if (order.isEmpty()) return emptyList()

        // The only step that needs real Readium href resolution — everything else
        // (dedup, ordering, fallback) is the plain-data collapseTocToChapterSpec below,
        // so it can be unit-tested without a real Uri (see this class's test file).
        val tocMatches = pub.tableOfContents.flattenToc().mapNotNull { tocLink ->
            val spineIndex = order.indexOfFirstWithHref(tocLink.url().removeFragment())
                ?: return@mapNotNull null
            spineIndex to tocLink.title?.trim()?.takeIf { it.isNotEmpty() }
        }

        return collapseTocToChapterSpec(tocMatches, order.map { it.title }).mapIndexed { chapterIndex, (spineIndex, title) ->
            val link = order[spineIndex]
            EpubChapter(
                chapter = ReaderChapter(title = title, index = chapterIndex) {
                    withContext(Dispatchers.IO) { fetchAndClean(pub, link) }.orEmpty()
                },
                spineIndex = spineIndex,
            )
        }
    }

    // ── TOC sidebar (#596) ───────────────────────────────────────────────────────

    /**
     * Builds the full-fidelity, nested table of contents for the sidebar — unlike
     * [buildChapters], this keeps every TOC entry (no spine-item collapsing) since it
     * drives precise on-screen navigation rather than Read Aloud chapter boundaries.
     * [Publication.locatorFromLink] resolves each entry's href (fragment included) to a
     * real [Locator], so tapping an entry can navigate straight to its anchor rather than
     * only the containing spine file. Entries whose href doesn't resolve to any resource
     * (a malformed TOC) are skipped.
     */
    internal fun buildTocEntries(pub: Publication): List<EpubTocEntry> =
        buildTocEntries(pub.tableOfContents, level = 0) { link ->
            pub.locatorFromLink(link)?.toJSON()?.toString()
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
        val source: ContentSource,
        val publication: Publication,
    ) : EpubPublicationState()

    /** Non-recoverable error opening the publication. */
    data class Error(val message: String) : EpubPublicationState()

    /** Publication is DRM-restricted (e.g. Adobe ADEPT, LCP) — Libravault has no
     * decryption support, so it's surfaced distinctly from [Error] to show
     * DRM-specific copy instead of a raw parser error message. */
    data class DrmProtected(val schemeName: String?) : EpubPublicationState()
}

// ── TOC sidebar entry (#596) ─────────────────────────────────────────────────────

/**
 * One row of the EPUB TOC sidebar, built from [Publication.tableOfContents] by
 * [EpubReaderViewModel.buildTocEntries]. [level] is the nesting depth (0 = top-level)
 * used to indent the sidebar row, mirroring [xyz.libravault.feature.reader.markdown.toc.TocEntry.level].
 * [locatorJson] is a serialized Readium [Locator] — the same format
 * [EpubReaderViewModel.goToLocatorJson] already accepts for bookmark navigation — so
 * tapping an entry reuses that existing navigation path rather than needing a new one.
 */
data class EpubTocEntry(
    val title: String,
    val level: Int,
    val locatorJson: String,
)

// Flattens a Readium TOC tree (parent immediately followed by its own children, depth-
// first) into chapter-collapsing order. Deliberately not `List<Link>.flatten()` from
// readium-shared, which also splices in `alternates` (different-language/format variants
// of the same resource, not sub-chapters) between a link and its children.
internal fun List<Link>.flattenToc(): List<Link> =
    flatMap { listOf(it) + it.children.flattenToc() }

/** Finds which built chapter "owns" the given spine index — the chapter itself if it
 * starts there, otherwise the nearest preceding chapter (e.g. a locator pointing at
 * a cover/copyright spine item with no TOC entry of its own is attributed to the
 * chapter before it, or the first chapter if it's before all of them). */
internal fun chapterIndexForSpineIndex(
    chapters: List<EpubReaderViewModel.EpubChapter>,
    spineIndex: Int,
): Int {
    if (chapters.isEmpty()) return 0
    val exact = chapters.indexOfFirst { it.spineIndex == spineIndex }
    if (exact >= 0) return exact
    val preceding = chapters.indexOfLast { it.spineIndex <= spineIndex }
    return if (preceding >= 0) preceding else 0
}

/**
 * The pure part of [EpubReaderViewModel.buildChapters]: given each TOC entry's already-
 * resolved spine index (or null title, meaning the TOC link itself had none) in TOC
 * order, plus every spine item's own title (used both as a per-entry title fallback and
 * to size the "no usable TOC" fallback), produces the deduped (spineIndex, title) pairs
 * in ascending spine order — *not* TOC-encounter order. A well-formed EPUB's nav
 * doc/NCX already lists entries in spine order, so this is a no-op for the common case,
 * but nothing about the EPUB spec guarantees that, and [chapterIndexForSpineIndex]'s
 * "nearest preceding chapter" search assumes ascending order to be correct. Sorting here
 * once, rather than trusting the input, keeps both that and Read Aloud's "next chapter"
 * walking physical reading order even against a malformed/reordered TOC.
 *
 * Takes no Readium types so it's testable without a real `android.net.Uri` — see this
 * class's test file's note on why `Link`/`Url` aren't safely constructible in a plain
 * JVM unit test here.
 */
internal fun collapseTocToChapterSpec(
    tocMatches: List<Pair<Int, String?>>,
    spineTitles: List<String?>,
): List<Pair<Int, String>> {
    val titleBySpineIndex = LinkedHashMap<Int, String>()
    for ((spineIndex, tocTitle) in tocMatches) {
        if (spineIndex !in spineTitles.indices) continue
        if (!titleBySpineIndex.containsKey(spineIndex)) {
            titleBySpineIndex[spineIndex] = tocTitle
                ?: spineTitles[spineIndex]
                ?: "Chapter ${spineIndex + 1}"
        }
    }

    return titleBySpineIndex.ifEmpty {
        spineTitles.indices.associateWith { i -> spineTitles[i] ?: "Chapter ${i + 1}" }
    }.toList().sortedBy { (spineIndex, _) -> spineIndex }
}

/**
 * The pure part of [EpubReaderViewModel.buildTocEntries]: walks a TOC tree depth-first
 * (parent immediately followed by its own children, [level] tracking nesting depth),
 * skipping any [Link] [locatorJsonFor] can't resolve (a malformed TOC entry). Takes
 * [locatorJsonFor] as a parameter rather than calling [Publication.locatorFromLink]
 * directly so the recursion/level/fallback-title logic is testable without a real
 * `android.net.Uri` — see [collapseTocToChapterSpec]'s doc for why that matters here.
 */
internal fun buildTocEntries(
    links: List<Link>,
    level: Int,
    locatorJsonFor: (Link) -> String?,
): List<EpubTocEntry> =
    links.flatMap { link ->
        val entry = locatorJsonFor(link)?.let { json ->
            EpubTocEntry(
                title = link.title?.trim()?.takeIf { it.isNotEmpty() } ?: "Untitled",
                level = level,
                locatorJson = json,
            )
        }
        listOfNotNull(entry) + buildTocEntries(link.children, level + 1, locatorJsonFor)
    }
