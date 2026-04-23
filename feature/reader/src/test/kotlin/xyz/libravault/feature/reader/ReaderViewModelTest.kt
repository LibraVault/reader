package xyz.libravault.feature.reader

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import xyz.libravault.core.domain.model.Bookmark
import xyz.libravault.core.domain.model.Highlight
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.core.domain.model.ReadingProgress
import xyz.libravault.core.domain.usecase.AddBookmarkUseCase
import xyz.libravault.core.domain.usecase.AddHighlightUseCase
import xyz.libravault.core.domain.usecase.DeleteBookmarkUseCase
import xyz.libravault.core.domain.usecase.DeleteHighlightUseCase
import xyz.libravault.core.domain.usecase.GetLibraryItemUseCase
import xyz.libravault.core.domain.usecase.GetReadingProgressUseCase
import xyz.libravault.core.domain.usecase.ObserveBookmarksUseCase
import xyz.libravault.core.domain.usecase.ObserveHighlightsUseCase
import xyz.libravault.core.domain.usecase.SaveReadingProgressUseCase
import xyz.libravault.core.logger.LibravaultLogger
import java.time.Instant

@ExtendWith(MockKExtension::class)
class ReaderViewModelTest {

    // ── Fakes ────────────────────────────────────────────────────────────────

    private val fakeItem = LibraryItem(
        id            = 1L,
        vaultFolderId = 1L,
        filePath      = "content://test/book.epub",
        title         = "Test Book",
        author        = "Test Author",
        format        = MediaFormat.EPUB,
    )

    private val fakeProgress = ReadingProgress(
        itemId      = 1L,
        positionCfi = "epubcfi(/6/4!/4/2/1:0)",
        lastReadAt  = Instant.now(),
    )

    private val getItem           = mockk<GetLibraryItemUseCase>()
    private val getProgress       = mockk<GetReadingProgressUseCase>()
    private val saveProgress      = mockk<SaveReadingProgressUseCase>(relaxed = true)
    private val observeBookmarks  = mockk<ObserveBookmarksUseCase>()
    private val addBookmark       = mockk<AddBookmarkUseCase>(relaxed = true)
    private val deleteBookmark    = mockk<DeleteBookmarkUseCase>(relaxed = true)
    private val observeHighlights = mockk<ObserveHighlightsUseCase>()
    private val addHighlight      = mockk<AddHighlightUseCase>(relaxed = true)
    private val deleteHighlight   = mockk<DeleteHighlightUseCase>(relaxed = true)
    private val logger            = mockk<LibravaultLogger>(relaxed = true)

    @BeforeEach
    fun setUp() {
        // UnconfinedTestDispatcher runs viewModelScope coroutines eagerly/synchronously.
        // The init coroutine completes before tests start collecting, so only the
        // final state is observable — no loading state in emissions.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { getItem(any()) }           returns fakeItem
        coEvery { getProgress(any()) }       returns fakeProgress
        coEvery { observeBookmarks(any()) }  returns flowOf(emptyList())
        coEvery { observeHighlights(any()) } returns flowOf(emptyList())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(itemId: Long = 1L): ReaderViewModel {
        coEvery { getItem(itemId) }           returns fakeItem
        coEvery { getProgress(itemId) }       returns fakeProgress
        coEvery { observeBookmarks(itemId) }  returns flowOf(emptyList())
        coEvery { observeHighlights(itemId) } returns flowOf(emptyList())

        return ReaderViewModel(
            savedStateHandle  = SavedStateHandle(mapOf("itemId" to itemId)),
            getItem           = getItem,
            getProgress       = getProgress,
            saveProgress      = saveProgress,
            observeBookmarks  = observeBookmarks,
            addBookmark       = addBookmark,
            deleteBookmark    = deleteBookmark,
            observeHighlights = observeHighlights,
            addHighlight      = addHighlight,
            deleteHighlight   = deleteHighlight,
            logger            = logger,
        )
    }

    // ── Init ─────────────────────────────────────────────────────────────────

    @Test
    fun `loads item and progress on init`() = runTest {
        // init coroutine completes synchronously — first emission is already loaded
        viewModel().uiState.test {
            val loaded = awaitItem()
            assertFalse(loaded.isLoading)
            assertNotNull(loaded.item)
            assertEquals("Test Book", loaded.item!!.title)
            assertEquals(fakeProgress.positionCfi, loaded.progress?.positionCfi)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `emits error state when item not found`() = runTest {
        coEvery { getItem(99L) }           returns null
        coEvery { getProgress(99L) }       returns null
        coEvery { observeBookmarks(99L) }  returns flowOf(emptyList())
        coEvery { observeHighlights(99L) } returns flowOf(emptyList())

        val vm = ReaderViewModel(
            savedStateHandle  = SavedStateHandle(mapOf("itemId" to 99L)),
            getItem           = getItem,
            getProgress       = getProgress,
            saveProgress      = saveProgress,
            observeBookmarks  = observeBookmarks,
            addBookmark       = addBookmark,
            deleteBookmark    = deleteBookmark,
            observeHighlights = observeHighlights,
            addHighlight      = addHighlight,
            deleteHighlight   = deleteHighlight,
            logger            = logger,
        )

        // init coroutine already completed — first emission is the error state
        vm.uiState.test {
            val error = awaitItem()
            assertNotNull(error.error)
            assertNull(error.item)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Toolbar ───────────────────────────────────────────────────────────────

    @Test
    fun `centre tap toggles toolbar visibility`() = runTest {
        val vm = viewModel()
        vm.uiState.test {
            awaitItem() // loaded state (loading already completed)
            assertTrue(vm.uiState.value.showToolbar)

            vm.onCentreTap()
            assertFalse(awaitItem().showToolbar)

            vm.onCentreTap()
            assertTrue(awaitItem().showToolbar)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    @Test
    fun `font size is clamped to valid range`() = runTest {
        val vm = viewModel()
        vm.onFontSizeChanged(5.0f)
        assertEquals(2.0f, vm.uiState.value.settings.fontSize)

        vm.onFontSizeChanged(0.1f)
        assertEquals(0.8f, vm.uiState.value.settings.fontSize)
    }

    @Test
    fun `settings sheet shows and hides`() = runTest {
        val vm = viewModel()
        assertFalse(vm.uiState.value.showSettingsSheet)
        vm.showSettings()
        assertTrue(vm.uiState.value.showSettingsSheet)
        vm.hideSettings()
        assertFalse(vm.uiState.value.showSettingsSheet)
    }

    // ── Progress ──────────────────────────────────────────────────────────────

    @Test
    fun `epub position change triggers save`() = runTest {
        val vm  = viewModel()
        val cfi = "epubcfi(/6/8!/4/2/1:42)"
        vm.onEpubPositionChanged(cfi)

        val saved = slot<ReadingProgress>()
        coVerify { saveProgress(capture(saved)) }
        assertEquals(cfi, saved.captured.positionCfi)
        assertEquals(1L, saved.captured.itemId)
    }

    @Test
    fun `pdf page change triggers save`() = runTest {
        val vm = viewModel()
        vm.onPdfPageChanged(7)

        val saved = slot<ReadingProgress>()
        coVerify { saveProgress(capture(saved)) }
        assertEquals(7, saved.captured.pageIndex)
    }

    // ── Bookmarks ─────────────────────────────────────────────────────────────

    @Test
    fun `add bookmark calls use case with correct itemId`() = runTest {
        val vm  = viewModel()
        val ref = "epubcfi(/6/4!/4/2/1:0)"
        vm.addBookmark(ref, "My bookmark")

        val saved = slot<Bookmark>()
        coVerify { addBookmark(capture(saved)) }
        assertEquals(1L, saved.captured.itemId)
        assertEquals(ref, saved.captured.positionRef)
        assertEquals("My bookmark", saved.captured.label)
    }

    @Test
    fun `remove bookmark delegates to delete use case`() = runTest {
        viewModel().removeBookmark(42L)
        coVerify { deleteBookmark(42L) }
    }

    // ── Highlights ────────────────────────────────────────────────────────────

    @Test
    fun `add highlight saves with correct defaults`() = runTest {
        val vm = viewModel()
        vm.addHighlight("epubcfi(/6/4!/4:10)", "selected text")

        val saved = slot<Highlight>()
        coVerify { addHighlight(capture(saved)) }
        assertEquals(1L, saved.captured.itemId)
        assertEquals("selected text", saved.captured.highlightedText)
        assertEquals("#FFE066", saved.captured.colorHex)
    }
}
