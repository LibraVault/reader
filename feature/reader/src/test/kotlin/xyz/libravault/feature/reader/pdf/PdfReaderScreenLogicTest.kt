package xyz.libravault.feature.reader.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure decision logic extracted from [PdfReaderScreen] (#606, Phase 7). No
 * Android/Robolectric dependency — see docs/TEST_COVERAGE_PRD.md §1b for why
 * the rendered-page content itself (the other 90% of this screen's lines)
 * can't be covered the same way [PlayerScreen]/[SettingsScreen] were: this
 * repo's Robolectric (4.12.2, sdk 34) instruments the real
 * `android.graphics.pdf.PdfRenderer` class without throwing, but its native
 * PDF-parsing backend isn't actually functional under it — a real,
 * byte-valid single-page PDF reproducibly reports `pageCount == 0`.
 */
class PdfReaderScreenLogicTest {

    // ── coercePageIndex ─────────────────────────────────────────────────────

    @Test
    fun `coercePageIndex clamps within range`() {
        assertEquals(3, coercePageIndex(3, pageCount = 10))
    }

    @Test
    fun `coercePageIndex clamps a negative page up to 0`() {
        assertEquals(0, coercePageIndex(-5, pageCount = 10))
    }

    @Test
    fun `coercePageIndex clamps a too-large page down to the last index`() {
        assertEquals(9, coercePageIndex(50, pageCount = 10))
    }

    @Test
    fun `coercePageIndex returns 0 for a zero-page document instead of throwing`() {
        // Regression guard: a bare `page.coerceIn(0, pageCount - 1)` throws
        // IllegalArgumentException here because 0 <= -1 is false. This is what
        // PdfReaderScreen did inline before this extraction.
        assertEquals(0, coercePageIndex(0, pageCount = 0))
        assertEquals(0, coercePageIndex(4, pageCount = 0))
    }

    // ── resolvePaginatedTapZone ──────────────────────────────────────────────

    @Test
    fun `resolvePaginatedTapZone resolves the left third to PREVIOUS`() {
        assertEquals(PdfTapZone.PREVIOUS, resolvePaginatedTapZone(tapXDp = 10f, widthDp = 300f))
    }

    @Test
    fun `resolvePaginatedTapZone resolves the right third to NEXT`() {
        assertEquals(PdfTapZone.NEXT, resolvePaginatedTapZone(tapXDp = 290f, widthDp = 300f))
    }

    @Test
    fun `resolvePaginatedTapZone resolves the middle third to CENTRE`() {
        assertEquals(PdfTapZone.CENTRE, resolvePaginatedTapZone(tapXDp = 150f, widthDp = 300f))
    }

    // ── isCentreTapZone ───────────────────────────────────────────────────────

    @Test
    fun `isCentreTapZone is true in the middle third`() {
        assertTrue(isCentreTapZone(tapXDp = 150f, widthDp = 300f))
    }

    @Test
    fun `isCentreTapZone is false in the outer thirds`() {
        assertFalse(isCentreTapZone(tapXDp = 10f, widthDp = 300f))
        assertFalse(isCentreTapZone(tapXDp = 290f, widthDp = 300f))
    }

    // ── renderedPageHeightPx ──────────────────────────────────────────────────

    @Test
    fun `renderedPageHeightPx preserves aspect ratio when scaling to the target width`() {
        // A 200x400 (1:2) PDF page scaled to a 100px-wide bitmap should be 200px tall.
        assertEquals(200, renderedPageHeightPx(pageWidthPx = 200, pageHeightPx = 400, targetWidthPx = 100))
    }

    @Test
    fun `renderedPageHeightPx handles a square page`() {
        assertEquals(100, renderedPageHeightPx(pageWidthPx = 200, pageHeightPx = 200, targetWidthPx = 100))
    }

    // ── pdfOpenErrorMessage ───────────────────────────────────────────────────

    @Test
    fun `pdfOpenErrorMessage reports permission-denied for a SecurityException`() {
        assertEquals(
            "Permission denied — the file cannot be read from this source.",
            pdfOpenErrorMessage(SecurityException("no access")),
        )
    }

    @Test
    fun `pdfOpenErrorMessage includes the exception message for other failures`() {
        assertEquals(
            "Could not open the PDF: file is corrupt",
            pdfOpenErrorMessage(IllegalStateException("file is corrupt")),
        )
    }

    // ── pageIndicatorText ─────────────────────────────────────────────────────

    @Test
    fun `pageIndicatorText is 1-based over the total page count`() {
        assertEquals("1 / 42", pageIndicatorText(currentPage = 0, pageCount = 42))
        assertEquals("42 / 42", pageIndicatorText(currentPage = 41, pageCount = 42))
    }
}
