package xyz.libravault.feature.reader.pdf

import android.content.Context
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.PDFBoxResourceLoader
import xyz.libravault.feature.reader.epub.EpubTextPreprocessor

/**
 * Lazy, cached per-page text extraction for PDF Read Aloud (#591 Phase 3), via
 * pdfbox-android — fully on-device, no network (see AGENTS.md's networking rule).
 *
 * A page's text is only ever extracted the first time [getPageText] is actually asked
 * for it, mirroring [xyz.libravault.feature.reader.epub.EpubReaderViewModel]'s on-demand
 * spine reads (`fetchAndClean`) — building every page's text up front would defeat the
 * point of chapter-based lazy loading for a large PDF. Once extracted, a page's cleaned
 * text is cached for the lifetime of this instance (i.e. one open document).
 *
 * One chapter per page (issue #597's MVP scope) — matching iOS's existing `PDFParser`
 * (PDFKit-based, "Page 1", "Page 2", …) rather than real outline/bookmark extraction,
 * which is explicit follow-up scope.
 *
 * Not thread-safe — callers (here, [PdfReaderViewModel]) are responsible for confining
 * calls to a single background dispatcher, the same contract [PdfReaderScreen]'s
 * [android.graphics.pdf.PdfRenderer] usage already relies on via its own `renderMutex`.
 */
class PdfTextExtractor(private val appContext: Context) {

    private var document: PDDocument? = null
    private val pageTextCache = mutableMapOf<Int, String>()

    /**
     * Opens [pfd] for text extraction and returns the page count, or null if the file
     * can't be parsed as a PDF. Closes any previously-open document first. Takes
     * ownership of [pfd] (closed by [close], directly or via the input stream reaching
     * EOF/error during [PDDocument.load]). Does real file I/O — call from a background
     * thread.
     */
    fun open(pfd: ParcelFileDescriptor): Int? {
        close()
        return runCatching {
            PDFBoxResourceLoader.init(appContext.applicationContext)
            val doc = PDDocument.load(ParcelFileDescriptor.AutoCloseInputStream(pfd))
            document = doc
            doc.numberOfPages
        }.getOrElse {
            runCatching { pfd.close() }
            null
        }
    }

    /**
     * Extracts (or returns the cached) plain text for [pageIndex] (0-based), cleaned
     * via [EpubTextPreprocessor.clean] — the same footnote/page-number/decorative-
     * separator stripping already shared by Markdown/EPUB. Returns null if no document
     * is open, [pageIndex] is out of range, extraction fails, or the page has no
     * narratable text (e.g. a blank or image-only page) — mirroring
     * `EpubReaderViewModel.fetchAndClean`'s "blank means nothing to say" contract, not
     * "end of book"; callers walking chapters sequentially treat null as an empty page
     * to skip past, same as EPUB already does for a blank spine item. Does real
     * parsing work — call from a background thread.
     */
    fun getPageText(pageIndex: Int): String? {
        pageTextCache[pageIndex]?.let { return it.takeIf { text -> text.isNotBlank() } }
        val doc = document ?: return null
        if (pageIndex !in 0 until doc.numberOfPages) return null
        val raw = runCatching {
            PDFTextStripper().apply {
                // PDFTextStripper page numbers are 1-based; pageIndex here is 0-based.
                startPage = pageIndex + 1
                endPage = pageIndex + 1
            }.getText(doc)
        }.getOrNull() ?: return null
        val cleaned = EpubTextPreprocessor.clean(raw)
        pageTextCache[pageIndex] = cleaned
        return cleaned.takeIf { it.isNotBlank() }
    }

    fun close() {
        document?.let { runCatching { it.close() } }
        document = null
        pageTextCache.clear()
    }
}
