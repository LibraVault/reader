package xyz.libravault.feature.reader.pdf

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.libravault.core.domain.model.ContentSource
import xyz.libravault.core.vaultcontent.VaultMemfdFallback
import xyz.libravault.core.vaultcontent.VaultProxyFdHost
import xyz.libravault.core.vaultstore.VaultSessionManager
import xyz.libravault.core.vaultstore.hexToFileId
import javax.inject.Inject

/**
 * Resolves a [ContentSource] to a [ParcelFileDescriptor] for [PdfReaderScreen]'s
 * [android.graphics.pdf.PdfRenderer]. The first ViewModel `PdfReaderScreen` has
 * had — previously a bare composable, since it needed no Hilt-injected
 * resources until #505 added the vault-backed path.
 *
 * [ContentSource.VaultEntry] resolution is a verbatim port of what
 * `VaultPdfReaderScreen` (feature:vault, deleted by #505) did: proxy fd first
 * — validated on real hardware, decrypts lazily, no extra memory — falling
 * back to [VaultMemfdFallback] if the proxy fd fails on this device. Never
 * writes decrypted bytes to disk either way.
 *
 * Also owns the Read Aloud (#591 Phase 3) page-based chapter walk — see the
 * "Read Aloud" section below — via a second, independent [PdfTextExtractor]
 * document handle, opened lazily only once a Read Aloud session actually starts.
 */
@HiltViewModel
class PdfReaderViewModel @Inject constructor(
    private val sessionManager: VaultSessionManager,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    // One host per ViewModel instance (i.e. per composition of PdfReaderScreen),
    // not per open — matches VaultProxyFdHost's own doc ("should be called
    // [close()] ... not per-file"). Created lazily so the plain-file path never
    // spins up its HandlerThread.
    private var vaultProxyFdHost: VaultProxyFdHost? = null

    suspend fun openFileDescriptor(source: ContentSource): ParcelFileDescriptor =
        withContext(Dispatchers.IO) {
            when (source) {
                is ContentSource.RealFile ->
                    appContext.contentResolver.openFileDescriptor(Uri.parse(source.uriString), "r")
                        ?: throw IllegalStateException("Could not open the PDF — file may be inaccessible.")

                is ContentSource.VaultEntry -> {
                    val store = sessionManager.requireUnlocked(source.vaultId)
                    val reader = store.openReader(source.fileIdHex.hexToFileId())
                    val host = vaultProxyFdHost ?: VaultProxyFdHost(appContext).also { vaultProxyFdHost = it }
                    runCatching { host.open(reader) }.getOrElse { VaultMemfdFallback.open(reader) }
                }
            }
        }

    // ── Read Aloud (#591 Phase 3) — lazy, page-based chapter walk ──────────────
    //
    // One chapter per page (issue #597's MVP scope). Mirrors EpubReaderViewModel's
    // ttsSpineIndex / MarkdownReaderViewModel's ttsChapterIndexState: the cursor is
    // (re)anchored each time a Read Aloud session starts via getChapterTextFromPage(),
    // then walked by getNextChapterText()/getPreviousChapterText() as the session
    // advances. [textExtractor] opens a *separate* ParcelFileDescriptor from the one
    // PdfReaderScreen's PdfRenderer holds for bitmap display — pdfbox-android needs
    // its own document handle, and both VaultProxyFdHost and the plain-file path
    // support multiple concurrent opens of the same underlying file (see
    // VaultProxyFdHost's own doc), so this doesn't contend with the renderer.

    private var textExtractor: PdfTextExtractor? = null
    private var ttsPageCount: Int = 0
    private var ttsPageIndexState: Int = 0

    /** 0-based current TTS page, for the Player screen's chapter display/nav (#138). */
    val ttsChapterIndex: Int get() = ttsPageIndexState

    /** Total pages in the current document, for the same purpose. */
    val ttsChapterCount: Int get() = ttsPageCount

    private suspend fun ensureTextExtractor(source: ContentSource): PdfTextExtractor? {
        textExtractor?.let { return it }
        val pfd = runCatching { openFileDescriptor(source) }.getOrNull() ?: return null
        val extractor = PdfTextExtractor(appContext)
        val pageCount = withContext(Dispatchers.IO) { extractor.open(pfd) }
        if (pageCount == null) return null
        ttsPageCount = pageCount
        textExtractor = extractor
        return extractor
    }

    /**
     * Opens (if not already open) a text-extraction document for [source] and returns
     * the text of the page nearest [initialPageIndex] — mirrors
     * EpubReaderViewModel.getChapterTextFromProgression/MarkdownReaderViewModel's
     * getChapterTextFromProgression: anchors the TTS cursor to wherever the reader
     * currently is rather than always restarting from page 0. Also anchors the TTS
     * cursor so getNextChapterText/getPreviousChapterText advance from here. Returns
     * null if the document can't be opened, has no pages, or the anchored page has no
     * narratable text.
     */
    suspend fun getChapterTextFromPage(source: ContentSource, initialPageIndex: Int?): String? {
        val extractor = ensureTextExtractor(source) ?: return null
        if (ttsPageCount == 0) return null
        ttsPageIndexState = (initialPageIndex ?: 0).coerceIn(0, ttsPageCount - 1)
        return withContext(Dispatchers.IO) { extractor.getPageText(ttsPageIndexState) }
    }

    /**
     * Advances the TTS cursor to the next page and returns its text. Returns null at
     * the end of the document, so the caller knows to stop — same contract as
     * EpubReaderViewModel.getNextChapterText/MarkdownReaderViewModel.getNextChapterText.
     */
    suspend fun getNextChapterText(): String? {
        val extractor = textExtractor ?: return null
        val nextIndex = ttsPageIndexState + 1
        if (nextIndex !in 0 until ttsPageCount) return null
        val text = withContext(Dispatchers.IO) { extractor.getPageText(nextIndex) } ?: return null
        ttsPageIndexState = nextIndex
        return text
    }

    /**
     * Moves the TTS cursor back to the previous page and returns its text. Returns
     * null (and leaves the cursor unmoved) at the start of the document, mirroring
     * [getNextChapterText]'s end-of-document contract — used by the Player screen's
     * "previous chapter" control (#138).
     */
    suspend fun getPreviousChapterText(): String? {
        val extractor = textExtractor ?: return null
        val prevIndex = ttsPageIndexState - 1
        if (prevIndex !in 0 until ttsPageCount) return null
        val text = withContext(Dispatchers.IO) { extractor.getPageText(prevIndex) } ?: return null
        ttsPageIndexState = prevIndex
        return text
    }

    override fun onCleared() {
        super.onCleared()
        vaultProxyFdHost?.close()
        textExtractor?.close()
    }
}
