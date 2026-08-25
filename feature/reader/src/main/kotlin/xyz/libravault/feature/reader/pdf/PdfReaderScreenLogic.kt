package xyz.libravault.feature.reader.pdf

/**
 * Clamps [page] into the valid index range for a document with [pageCount]
 * pages. A bare `page.coerceIn(0, pageCount - 1)` — what [PdfReaderScreen]
 * used inline before this extraction — throws `IllegalArgumentException`
 * whenever [pageCount] is 0, because `coerceIn` requires its bounds not be
 * inverted (`0 <= -1` is false). A validly-opened PDF with zero pages is a
 * legitimate (if unusual) document, not a corrupt one — [PdfReaderViewModel]
 * only throws for files that fail to open at all — so this returns 0 instead
 * of crashing the reader.
 */
internal fun coercePageIndex(page: Int, pageCount: Int): Int =
    if (pageCount <= 0) 0 else page.coerceIn(0, pageCount - 1)

/** Which action a tap lands on in [PdfPaginatedView]'s left/right/centre page-turn gesture. */
internal enum class PdfTapZone { PREVIOUS, NEXT, CENTRE }

/**
 * Left third of the page turns back, right third turns forward, the centre
 * third is reserved for [PdfPaginatedView]'s "toggle chrome" tap — mirrors
 * [isCentreTapZone]'s thirds for scrolling mode.
 */
internal fun resolvePaginatedTapZone(tapXDp: Float, widthDp: Float): PdfTapZone = when {
    tapXDp < widthDp * 0.33f -> PdfTapZone.PREVIOUS
    tapXDp > widthDp * 0.67f -> PdfTapZone.NEXT
    else -> PdfTapZone.CENTRE
}

/** Whether a tap in [PdfScrollingView] lands in the centre third that toggles chrome. */
internal fun isCentreTapZone(tapXDp: Float, widthDp: Float): Boolean =
    tapXDp in (widthDp * 0.33f)..(widthDp * 0.67f)

/**
 * Bitmap height for a PDF page rendered at [targetWidthPx], preserving the
 * page's own aspect ratio ([pageWidthPx] x [pageHeightPx], both in PDF points).
 */
internal fun renderedPageHeightPx(pageWidthPx: Int, pageHeightPx: Int, targetWidthPx: Int): Int {
    val ratio = pageHeightPx.toFloat() / pageWidthPx.toFloat()
    return (targetWidthPx * ratio).toInt()
}

/** Maps a PDF-open failure to the message [PdfReaderScreen] shows the user. */
internal fun pdfOpenErrorMessage(error: Throwable): String = when (error) {
    is SecurityException -> "Permission denied — the file cannot be read from this source."
    else -> "Could not open the PDF: ${error.message}"
}

/**
 * Shown instead of [PdfPaginatedView]/[PdfScrollingView] when [PdfRenderer] opened
 * the file successfully but reports zero pages. A validly-opened, 0-page PDF is not
 * an open failure ([pdfOpenErrorMessage] doesn't apply — [PdfReaderViewModel] never
 * throws for it), but composing either view unguarded means [PdfPageImage] calls
 * `renderer.openPage(0)` on a document with no valid page index, which
 * `android.graphics.pdf.PdfRenderer` throws `IllegalArgumentException` for — see #613.
 */
internal fun pdfEmptyDocumentMessage(): String = "This PDF has no pages to display."

/** "<n> / <total>" page indicator text shown in paginated mode. */
internal fun pageIndicatorText(currentPage: Int, pageCount: Int): String =
    "${currentPage + 1} / $pageCount"
