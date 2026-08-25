package xyz.libravault.feature.reader.markdown

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.libravault.core.domain.model.ContentSource
import xyz.libravault.core.logger.LibravaultLogger
import xyz.libravault.core.storage.MarkdownAssetResolver
import xyz.libravault.core.vaultstore.VaultSessionManager
import xyz.libravault.core.vaultstore.hexToFileId
import javax.inject.Inject

/**
 * ViewModel scoped to the Markdown reader sub-screen.
 *
 * Responsibilities:
 *  - Read the Markdown file at a SAF [Uri] into a [String] and expose it as a [StateFlow].
 *  - Resolve the file's parent [androidx.documentfile.provider.DocumentFile] (for
 *    relative image loading) once per load, alongside the text read.
 *
 * Deliberately separate from [xyz.libravault.feature.reader.ReaderViewModel], mirroring
 * [xyz.libravault.feature.reader.epub.EpubReaderViewModel]'s split: the parent handles
 * all persistence (progress, bookmarks); this one only manages the SAF read → text
 * pipeline. There's no native publication object to close on clear (unlike Readium),
 * so this is simpler than its EPUB counterpart.
 */
@HiltViewModel
class MarkdownReaderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val assetResolver: MarkdownAssetResolver,
    private val sessionManager: VaultSessionManager,
    private val logger: LibravaultLogger,
) : ViewModel() {

    private val _state = MutableStateFlow<MarkdownPublicationState>(MarkdownPublicationState.Idle)
    val state: StateFlow<MarkdownPublicationState> = _state.asStateFlow()

    // Read Aloud (#276) chapter walk — same shape as EpubReaderViewModel's
    // ttsSpineIndex: the chapter list is (re)built from the current document each time
    // a session starts, and the index advances as ReaderViewModel's completion-event
    // handler calls getNextChapterText().
    private var ttsChapters: List<MarkdownTtsChapter> = emptyList()
    private var ttsChapterIndexState: Int = 0

    /** 0-based current TTS chapter, for the Player screen's chapter display/nav (#138). */
    val ttsChapterIndex: Int get() = ttsChapterIndexState

    /** Total narratable chapters in the current document, for the same purpose. */
    val ttsChapterCount: Int get() = ttsChapters.size

    /**
     * Reads the Markdown file at [source] (#505 — a real file or an Encrypted
     * Vault entry). Idempotent — if the same [ContentSource] is already loaded
     * (or loading), does nothing. [vaultTreeUri] is the item's *unencrypted*
     * SAF vault folder (a different, unrelated concept from
     * [ContentSource.VaultEntry] — see the naming-collision note on
     * [xyz.libravault.feature.reader.ReaderUiState.vaultTreeUri]; only ever
     * non-null for [ContentSource.RealFile]) — used to resolve the file's
     * parent directory for relative image loading.
     *
     * Returns the launched [Job] so tests can `.join()` it — the body hops onto a
     * real [Dispatchers.IO] thread for the file read, which `runTest`'s virtual
     * scheduler can't wait for automatically since it isn't a child of the test's
     * own coroutine scope.
     */
    fun load(source: ContentSource, vaultTreeUri: Uri? = null): Job {
        val current = _state.value
        if (current is MarkdownPublicationState.Ready && current.source == source) return Job().apply { complete() }
        if (current is MarkdownPublicationState.Loading) return Job().apply { complete() }

        _state.value = MarkdownPublicationState.Loading

        return viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { readText(source) }
            _state.value = if (text != null) {
                logger.i(TAG, "Loaded Markdown file: $source (${text.length} chars)")
                val parentDirectory = if (source is ContentSource.RealFile) {
                    vaultTreeUri?.let {
                        withContext(Dispatchers.IO) {
                            assetResolver.findParentDirectory(it, Uri.parse(source.uriString))
                        }
                    }
                } else {
                    null // encrypted vault content: no relative-image resolution (#442 v1 scope)
                }
                MarkdownPublicationState.Ready(source, text, parentDirectory)
            } else {
                logger.e(TAG, "Failed to read Markdown file: $source")
                MarkdownPublicationState.Error("Couldn't read this file.")
            }
        }
    }

    /**
     * Builds the Read Aloud chapter walk from the currently-loaded document and
     * returns the text of the chapter nearest [initialScrollFraction] — mirrors
     * [xyz.libravault.feature.reader.epub.EpubReaderViewModel.getChapterTextFromProgression]:
     * anchors the TTS cursor to wherever the reader currently is rather than always
     * restarting from the top. Returns null if no document is loaded (Idle/Loading/Error)
     * or the document has no narratable chapters (e.g. empty file).
     */
    suspend fun getChapterTextFromProgression(initialScrollFraction: Double?): String? {
        val ready = _state.value as? MarkdownPublicationState.Ready ?: return null
        ttsChapters = MarkdownTtsTextExtractor.chaptersForNarration(ready.text)
        if (ttsChapters.isEmpty()) return null
        ttsChapterIndexState = sectionIndexForFraction(initialScrollFraction, ttsChapters.size) ?: 0
        return ttsChapters[ttsChapterIndexState].text
    }

    /**
     * Advances the TTS cursor to the next chapter and returns its text. Returns null
     * at the end of the document, so the caller knows to stop — same contract as
     * [xyz.libravault.feature.reader.epub.EpubReaderViewModel.getNextChapterText].
     */
    suspend fun getNextChapterText(): String? {
        val nextIndex = ttsChapterIndexState + 1
        val chapter = ttsChapters.getOrNull(nextIndex) ?: return null
        ttsChapterIndexState = nextIndex
        return chapter.text
    }

    /**
     * Moves the TTS cursor back to the previous chapter and returns its text.
     * Returns null (and leaves the cursor unmoved) at the start of the document,
     * mirroring [getNextChapterText]'s end-of-document contract — used by the
     * Player screen's "previous chapter" control (#138).
     */
    suspend fun getPreviousChapterText(): String? {
        val prevIndex = ttsChapterIndexState - 1
        val chapter = ttsChapters.getOrNull(prevIndex) ?: return null
        ttsChapterIndexState = prevIndex
        return chapter.text
    }

    private fun readText(source: ContentSource): String? = when (source) {
        is ContentSource.RealFile -> readRealFileText(Uri.parse(source.uriString))
        is ContentSource.VaultEntry -> readVaultText(source)
    }

    private fun readRealFileText(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            // Hard cap, mirroring EpubReaderViewModel.MAX_CHAPTER_BYTES — a
            // maliciously huge "Markdown" file shouldn't be able to exhaust memory
            // just because the SAF scanner recognized its extension.
            val bytes = stream.readBytes()
            if (bytes.size > MAX_FILE_BYTES) {
                logger.w(TAG, "Markdown file exceeds ${MAX_FILE_BYTES / (1024 * 1024)} MB cap (${bytes.size} B); refusing")
                return@runCatching null
            }
            String(bytes, Charsets.UTF_8)
        }
    }.getOrNull()

    /**
     * Vault-native counterpart to [readRealFileText] — a verbatim port of
     * `VaultReaderViewModel`'s (feature:vault, deleted by #505) Markdown
     * dispatch, plus one real fix: applies [MAX_FILE_BYTES], which that
     * vault-only path never had.
     */
    private fun readVaultText(source: ContentSource.VaultEntry): String? = runCatching {
        val store = sessionManager.requireUnlocked(source.vaultId)
        store.openReader(source.fileIdHex.hexToFileId()).use { reader ->
            if (reader.plainSize > MAX_FILE_BYTES) {
                logger.w(
                    TAG,
                    "Vault Markdown file exceeds ${MAX_FILE_BYTES / (1024 * 1024)} MB cap " +
                        "(${reader.plainSize} B); refusing",
                )
                return@runCatching null
            }
            reader.readAt(0L, reader.plainSize.toInt()).toString(Charsets.UTF_8)
        }
    }.getOrNull()

    internal companion object {
        const val TAG = "MarkdownReaderViewModel"
        internal const val MAX_FILE_BYTES = 10 * 1024 * 1024
    }
}
