package xyz.libravault.feature.reader.pdf

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.domain.model.ContentSource
import xyz.libravault.feature.reader.ReaderSettings

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
}
