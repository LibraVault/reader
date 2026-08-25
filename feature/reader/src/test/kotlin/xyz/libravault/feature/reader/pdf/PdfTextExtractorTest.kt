package xyz.libravault.feature.reader.pdf

import android.content.Context
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Real pdfbox-android parsing, not a mock — the same rigor
 * MarkdownReaderViewModelTest's vault-decrypt test applies: this exercises the actual
 * [PDDocument.load]/[com.tom_roush.pdfbox.text.PDFTextStripper] pipeline against a
 * genuinely-generated PDF, not a fake standing in for it. Runs on Robolectric (JVM, no
 * emulator) for a real [android.content.Context] — pdfbox-android's resource loader
 * needs one to resolve its bundled font-metrics assets, mirroring
 * MarkdownTableRenderingTest's/Migration6To7RealExecutionTest's existing Robolectric
 * setup in this codebase.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfTextExtractorTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    // PDType1Font.HELVETICA's static init (used by pdfFile() below to build test
    // fixtures) reaches PDFBoxResourceLoader for bundled AFM font-metrics assets —
    // it throws (and, worse, permanently poisons the class via NoClassDefFoundError
    // on every later reference in this classloader) unless the loader is initialized
    // first. PdfTextExtractor.open() also calls init(), but only after a document is
    // already open — too late for a fixture built before that. Explicit @Before here
    // makes every test's fixture-generation step independent of exercise-order,
    // rather than accidentally relying on some other test having opened a document
    // first.
    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
    }

    private fun pdfFile(vararg pageTexts: String): File {
        val file = File.createTempFile("pdf-text-extractor-test", ".pdf")
        file.deleteOnExit()
        PDDocument().use { doc ->
            pageTexts.forEach { text ->
                val page = PDPage()
                doc.addPage(page)
                if (text.isNotEmpty()) {
                    PDPageContentStream(doc, page).use { stream ->
                        stream.beginText()
                        stream.setFont(PDType1Font.HELVETICA, 12f)
                        stream.newLineAtOffset(72f, 700f)
                        stream.showText(text)
                        stream.endText()
                    }
                }
            }
            doc.save(file)
        }
        return file
    }

    private fun openPfd(file: File): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

    @Test
    fun `open returns the real page count`() {
        val extractor = PdfTextExtractor(context)

        val pageCount = extractor.open(openPfd(pdfFile("Hello page one", "Hello page two")))

        assertEquals(2, pageCount)
        extractor.close()
    }

    @Test
    fun `getPageText extracts real text per page`() {
        val extractor = PdfTextExtractor(context)
        extractor.open(openPfd(pdfFile("Hello page one", "Hello page two")))

        val page0 = extractor.getPageText(0)
        val page1 = extractor.getPageText(1)

        assertTrue("expected page 0 text, got: $page0", page0!!.contains("Hello page one"))
        assertTrue("expected page 1 text, got: $page1", page1!!.contains("Hello page two"))
        extractor.close()
    }

    @Test
    fun `getPageText caches extracted text across repeated calls`() {
        val extractor = PdfTextExtractor(context)
        extractor.open(openPfd(pdfFile("Cached text")))

        val first = extractor.getPageText(0)
        val second = extractor.getPageText(0)

        assertEquals(first, second)
        extractor.close()
    }

    @Test
    fun `getPageText returns null for an out-of-range page`() {
        val extractor = PdfTextExtractor(context)
        extractor.open(openPfd(pdfFile("Only page")))

        assertNull(extractor.getPageText(5))
        extractor.close()
    }

    @Test
    fun `getPageText returns null for a blank page`() {
        val extractor = PdfTextExtractor(context)
        extractor.open(openPfd(pdfFile("")))

        assertNull(extractor.getPageText(0))
        extractor.close()
    }

    @Test
    fun `getPageText returns null before any document is open`() {
        val extractor = PdfTextExtractor(context)

        assertNull(extractor.getPageText(0))
    }

    @Test
    fun `open on an unparseable file returns null instead of throwing`() {
        val badFile = File.createTempFile("not-a-pdf", ".pdf")
        badFile.deleteOnExit()
        badFile.writeText("this is not a real PDF")
        val extractor = PdfTextExtractor(context)

        val pageCount = extractor.open(openPfd(badFile))

        assertNull(pageCount)
    }

    @Test
    fun `open a second time closes the previous document`() {
        val extractor = PdfTextExtractor(context)
        extractor.open(openPfd(pdfFile("First document")))

        val secondCount = extractor.open(openPfd(pdfFile("Second document, page one", "Second document, page two")))

        assertEquals(2, secondCount)
        val page0 = extractor.getPageText(0)
        assertTrue("expected the second document's text, got: $page0", page0!!.contains("Second document"))
        extractor.close()
    }
}
