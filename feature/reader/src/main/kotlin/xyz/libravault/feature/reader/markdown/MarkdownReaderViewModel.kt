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
import xyz.libravault.core.logger.LibravaultLogger
import xyz.libravault.core.storage.MarkdownAssetResolver
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
    private val logger: LibravaultLogger,
) : ViewModel() {

    private val _state = MutableStateFlow<MarkdownPublicationState>(MarkdownPublicationState.Idle)
    val state: StateFlow<MarkdownPublicationState> = _state.asStateFlow()

    // Read Aloud (#276) chapter walk — same shape as EpubReaderViewModel's
    // ttsSpineIndex: the chapter list is (re)built from the current document each time
    // a session starts, and the index advances as ReaderViewModel's completion-event
    // handler calls getNextChapterText().
    private var ttsChapters: List<MarkdownTtsChapter> = emptyList()
    private var ttsChapterIndex: Int = 0

    /**
     * Reads the Markdown file at [uri]. Idempotent — if the same URI is already
     * loaded (or loading), does nothing. [vaultTreeUri] is the item's vault folder
     * (null for items with no vault association, e.g. opened via external intent) —
     * used to resolve the file's parent directory for relative image loading.
     *
     * Returns the launched [Job] so tests can `.join()` it — the body hops onto a
     * real [Dispatchers.IO] thread for the file read, which `runTest`'s virtual
     * scheduler can't wait for automatically since it isn't a child of the test's
     * own coroutine scope.
     */
    fun load(uri: Uri, vaultTreeUri: Uri? = null): Job {
        val current = _state.value
        if (current is MarkdownPublicationState.Ready && current.uri == uri) return Job().apply { complete() }
        if (current is MarkdownPublicationState.Loading) return Job().apply { complete() }

        _state.value = MarkdownPublicationState.Loading

        return viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { readText(uri) }
            _state.value = if (text != null) {
                logger.i(TAG, "Loaded Markdown file: $uri (${text.length} chars)")
                val parentDirectory = vaultTreeUri?.let {
                    withContext(Dispatchers.IO) { assetResolver.findParentDirectory(it, uri) }
                }
                MarkdownPublicationState.Ready(uri, text, parentDirectory)
            } else {
                logger.e(TAG, "Failed to read Markdown file: $uri")
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
        ttsChapterIndex = sectionIndexForFraction(initialScrollFraction, ttsChapters.size) ?: 0
        return ttsChapters[ttsChapterIndex].text
    }

    /**
     * Advances the TTS cursor to the next chapter and returns its text. Returns null
     * at the end of the document, so the caller knows to stop — same contract as
     * [xyz.libravault.feature.reader.epub.EpubReaderViewModel.getNextChapterText].
     */
    suspend fun getNextChapterText(): String? {
        val nextIndex = ttsChapterIndex + 1
        val chapter = ttsChapters.getOrNull(nextIndex) ?: return null
        ttsChapterIndex = nextIndex
        return chapter.text
    }

    private fun readText(uri: Uri): String? = runCatching {
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

    internal companion object {
        const val TAG = "MarkdownReaderViewModel"
        internal const val MAX_FILE_BYTES = 10 * 1024 * 1024
    }
}
