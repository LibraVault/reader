package xyz.libravault.feature.reader.pdf

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.domain.model.ContentSource
import xyz.libravault.feature.reader.ReaderSettings
import xyz.libravault.feature.reader.ScrollMode
import java.io.File

/**
 * Covers [PdfReaderScreen]'s own composable body — the branches that don't
 * depend on real [android.graphics.pdf.PdfRenderer] page content, which
 * (see docs/TEST_COVERAGE_PRD.md §1b / #606) can't be exercised under this
 * repo's Robolectric setup. [PdfReaderScreen] resolves its file through
 * [PdfReaderViewModel.openFileDescriptor] *before* ever touching
 * `PdfRenderer`, so a mocked ViewModel is enough to drive the error branch
 * for real, through the actual composable and its real [DisposableEffect]/
 * coroutine plumbing rather than just the extracted [pdfOpenErrorMessage]
 * pure function.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfReaderScreenStateTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val contentSource = ContentSource.RealFile("content://fake/doc.pdf")

    @Test
    fun `shows the permission-denied message when the file descriptor throws SecurityException`() {
        val viewModel = mockk<PdfReaderViewModel>()
        coEvery { viewModel.openFileDescriptor(any()) } throws SecurityException("nope")

        composeTestRule.setContent {
            PdfReaderScreen(
                contentSource = contentSource,
                initialPage   = 0,
                settings      = ReaderSettings(),
                onPageChanged = {},
                onCentreTap   = {},
                viewModel     = viewModel,
            )
        }

        composeTestRule.onNode(hasText("Permission denied", substring = true)).assertExists()
    }

    @Test
    fun `shows a generic open-failure message for other exceptions, including the original cause`() {
        val viewModel = mockk<PdfReaderViewModel>()
        coEvery { viewModel.openFileDescriptor(any()) } throws IllegalStateException("boom")

        composeTestRule.setContent {
            PdfReaderScreen(
                contentSource = contentSource,
                initialPage   = 0,
                settings      = ReaderSettings(),
                onPageChanged = {},
                onCentreTap   = {},
                viewModel     = viewModel,
            )
        }

        composeTestRule.onNode(hasText("Could not open the PDF: boom", substring = true)).assertExists()
    }

    // ── 0-page PDF (#613) ────────────────────────────────────────────────────
    //
    // §1c's investigation found PdfRenderer opens real PDF content without
    // throwing under this Robolectric setup, but always reports pageCount == 0
    // — the exact condition #613 needs covered end-to-end (not just the pure
    // coercePageIndex clamp), so a real, minimal single-page PDF is enough to
    // reproduce it here without a working native render backend.

    private fun emptyPdfFileDescriptor(): ParcelFileDescriptor {
        val bytes = """
            %PDF-1.1
            1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
            2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
            3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 3 3]>>endobj
            trailer<</Size 4/Root 1 0 R>>
            %%EOF
        """.trimIndent().toByteArray()
        val file = File.createTempFile("empty", ".pdf", ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir)
        file.writeBytes(bytes)
        file.deleteOnExit()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    @Test
    fun `shows the empty-document message instead of crashing for a 0-page PDF in paginated mode`() {
        val viewModel = mockk<PdfReaderViewModel>()
        coEvery { viewModel.openFileDescriptor(any()) } returns emptyPdfFileDescriptor()

        composeTestRule.setContent {
            PdfReaderScreen(
                contentSource = contentSource,
                initialPage   = 0,
                settings      = ReaderSettings(scrollMode = ScrollMode.PAGINATED),
                onPageChanged = {},
                onCentreTap   = {},
                viewModel     = viewModel,
            )
        }

        composeTestRule.onNode(hasText(pdfEmptyDocumentMessage(), substring = true)).assertExists()
    }

    @Test
    fun `shows the empty-document message instead of crashing for a 0-page PDF in scrolling mode`() {
        val viewModel = mockk<PdfReaderViewModel>()
        coEvery { viewModel.openFileDescriptor(any()) } returns emptyPdfFileDescriptor()

        composeTestRule.setContent {
            PdfReaderScreen(
                contentSource = contentSource,
                initialPage   = 0,
                settings      = ReaderSettings(scrollMode = ScrollMode.SCROLLING),
                onPageChanged = {},
                onCentreTap   = {},
                viewModel     = viewModel,
            )
        }

        composeTestRule.onNode(hasText(pdfEmptyDocumentMessage(), substring = true)).assertExists()
    }
}
