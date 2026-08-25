package xyz.libravault.feature.reader.pdf

import android.content.ContentResolver
import android.content.ContextWrapper
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModelStore
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import xyz.libravault.core.domain.model.ContentSource
import xyz.libravault.core.vaultstore.VaultSessionManager
import java.io.File

/**
 * Read Aloud (#591 Phase 3) page-based chapter walk — mirrors
 * MarkdownReaderViewModelTest's/EpubReaderViewModelTest's "Read Aloud chapter walk"
 * coverage for their own formats. Runs on Robolectric (JVM, no emulator) rather than
 * plain JUnit5+MockK like MarkdownReaderViewModelTest, because
 * [PdfReaderViewModel.getChapterTextFromPage] exercises real pdfbox-android parsing
 * (via [PdfTextExtractor]) against a genuinely-generated PDF, not a mock standing in
 * for the text-extraction pipeline — same rigor as [PdfTextExtractorTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfReaderViewModelTest {

    private val resolver = mockk<ContentResolver>()
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getContentResolver(): ContentResolver = resolver
    }
    private val sessionManager = mockk<VaultSessionManager>(relaxed = true)
    private val source = ContentSource.RealFile("content://test/doc.pdf")

    // Owns every ViewModel this test class creates so tearDown() can clear() them —
    // same #553 rationale as MarkdownReaderViewModelTest's viewModelStore.
    private val viewModelStore = ViewModelStore()

    // Same rationale as PdfTextExtractorTest's own @Before — stubPdf() below builds
    // fixtures with PDType1Font.HELVETICA before any PdfReaderViewModel call would
    // otherwise trigger PDFBoxResourceLoader.init() itself.
    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
    }

    private fun viewModel(): PdfReaderViewModel =
        PdfReaderViewModel(sessionManager, context).also { viewModelStore.put(it.toString(), it) }

    private fun stubPdf(vararg pageTexts: String) {
        val file = File.createTempFile("pdf-reader-viewmodel-test", ".pdf")
        file.deleteOnExit()
        PDDocument().use { doc ->
            pageTexts.forEach { text ->
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 12f)
                    stream.newLineAtOffset(72f, 700f)
                    stream.showText(text)
                    stream.endText()
                }
            }
            doc.save(file)
        }
        // A fresh fd per call, matching how a real ContentResolver behaves — the
        // extractor closes its fd once it finishes reading (see PdfTextExtractor.open),
        // so reusing a single already-closed ParcelFileDescriptor across calls would
        // fail in a way real usage never does.
        every { resolver.openFileDescriptor(any(), "r") } answers {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    // ── ttsChapterIndex / ttsChapterCount ─────────────────────────────────────

    @Test
    fun `ttsChapterIndex and ttsChapterCount are 0 before any Read Aloud session`() {
        val vm = viewModel()

        assertEquals(0, vm.ttsChapterIndex)
        assertEquals(0, vm.ttsChapterCount)
    }

    // ── getChapterTextFromPage ─────────────────────────────────────────────────

    @Test
    fun `getChapterTextFromPage returns real text and anchors the cursor at the given page`() = runTest {
        stubPdf("Page one text", "Page two text", "Page three text")
        val vm = viewModel()

        val text = vm.getChapterTextFromPage(source, initialPageIndex = 1)

        assertTrue("expected page two text, got: $text", text!!.contains("Page two text"))
        assertEquals(1, vm.ttsChapterIndex)
        assertEquals(3, vm.ttsChapterCount)
    }

    @Test
    fun `getChapterTextFromPage with a null page starts at page 0`() = runTest {
        stubPdf("Page one text", "Page two text")
        val vm = viewModel()

        val text = vm.getChapterTextFromPage(source, initialPageIndex = null)

        assertTrue(text!!.contains("Page one text"))
        assertEquals(0, vm.ttsChapterIndex)
    }

    @Test
    fun `getChapterTextFromPage clamps an out-of-range page to the last page`() = runTest {
        stubPdf("Only page")
        val vm = viewModel()

        val text = vm.getChapterTextFromPage(source, initialPageIndex = 99)

        assertTrue(text!!.contains("Only page"))
        assertEquals(0, vm.ttsChapterIndex)
    }

    @Test
    fun `getChapterTextFromPage returns null when the document can't be opened`() = runTest {
        every { resolver.openFileDescriptor(any(), "r") } returns null
        val vm = viewModel()

        assertNull(vm.getChapterTextFromPage(source, initialPageIndex = 0))
    }

    @Test
    fun `getChapterTextFromPage reuses the already-open text extractor on a later call`() = runTest {
        stubPdf("Page one text", "Page two text")
        val vm = viewModel()
        vm.getChapterTextFromPage(source, initialPageIndex = 0)

        vm.getChapterTextFromPage(source, initialPageIndex = 1)

        // A second document open would mean ensureTextExtractor() isn't actually
        // caching — Read Aloud would reopen (and re-decrypt, for a vault source)
        // the whole document on every chapter-nav call instead of once per session.
        verify(exactly = 1) { resolver.openFileDescriptor(any(), "r") }
    }

    // ── getNextChapterText / getPreviousChapterText ───────────────────────────

    @Test
    fun `getNextChapterText walks forward from the anchored page`() = runTest {
        stubPdf("Page one text", "Page two text")
        val vm = viewModel()
        vm.getChapterTextFromPage(source, initialPageIndex = 0)

        val next = vm.getNextChapterText()

        assertTrue(next!!.contains("Page two text"))
        assertEquals(1, vm.ttsChapterIndex)
    }

    @Test
    fun `getNextChapterText returns null at the end of the document`() = runTest {
        stubPdf("Page one text", "Page two text")
        val vm = viewModel()
        vm.getChapterTextFromPage(source, initialPageIndex = 1)

        assertNull(vm.getNextChapterText())
    }

    @Test
    fun `getNextChapterText returns null before any session has anchored the cursor`() = runTest {
        stubPdf("Page one text")
        val vm = viewModel()

        assertNull(vm.getNextChapterText())
    }

    @Test
    fun `getPreviousChapterText walks back and updates ttsChapterIndex`() = runTest {
        stubPdf("Page one text", "Page two text", "Page three text")
        val vm = viewModel()
        vm.getChapterTextFromPage(source, initialPageIndex = 2)

        val previous = vm.getPreviousChapterText()

        assertTrue(previous!!.contains("Page two text"))
        assertEquals(1, vm.ttsChapterIndex)
    }

    @Test
    fun `getPreviousChapterText returns null and leaves the cursor unmoved at the start`() = runTest {
        stubPdf("Page one text", "Page two text")
        val vm = viewModel()
        vm.getChapterTextFromPage(source, initialPageIndex = 0)

        val previous = vm.getPreviousChapterText()

        assertNull(previous)
        assertEquals(0, vm.ttsChapterIndex)
    }
}
