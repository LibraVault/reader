package xyz.libravault.feature.reader

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.media3.session.MediaController
import app.cash.turbine.test
import com.google.common.util.concurrent.SettableFuture
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
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
import xyz.libravault.core.domain.usecase.GetVaultFolderUseCase
import xyz.libravault.core.domain.usecase.ObserveBookmarksUseCase
import xyz.libravault.core.domain.usecase.ObserveHighlightsUseCase
import xyz.libravault.core.domain.usecase.SaveReadingProgressUseCase
import xyz.libravault.core.domain.usecase.UpdateBookmarkNoteUseCase
import xyz.libravault.core.logger.LibravaultLogger
import xyz.libravault.feature.player.service.PlaybackStateHolder
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

    private val getItem            = mockk<GetLibraryItemUseCase>()
    private val getVaultFolder     = mockk<GetVaultFolderUseCase>(relaxed = true)
    private val openFile           = mockk<xyz.libravault.core.storage.usecase.OpenFileUseCase>(relaxed = true)
    private val getProgress        = mockk<GetReadingProgressUseCase>()
    private val saveProgress       = mockk<SaveReadingProgressUseCase>(relaxed = true)
    private val observeBookmarks   = mockk<ObserveBookmarksUseCase>()
    private val addBookmark        = mockk<AddBookmarkUseCase>(relaxed = true)
    private val deleteBookmark     = mockk<DeleteBookmarkUseCase>(relaxed = true)
    private val updateBookmarkNote = mockk<UpdateBookmarkNoteUseCase>(relaxed = true)
    private val observeHighlights  = mockk<ObserveHighlightsUseCase>()
    private val addHighlight       = mockk<AddHighlightUseCase>(relaxed = true)
    private val deleteHighlight    = mockk<DeleteHighlightUseCase>(relaxed = true)
    private val logger             = mockk<LibravaultLogger>(relaxed = true)

    // PR #11: audiobook mini-player state relay + MediaController future.
    // Use a real PlaybackStateHolder (cheap, no required init) and a SettableFuture
    // pre-populated with a relaxed mock controller — matches the pattern in
    // feature/player/src/test/.../PlayerViewModelTest.kt.
    private val playbackStateHolder = PlaybackStateHolder()
    private val mockController      = mockk<MediaController>(relaxed = true)
    private val controllerFuture    = SettableFuture.create<MediaController>()

    // PR #11: skip-duration setting is read from SharedPreferences via this Context.
    // The unit tests in this file never call seekBackAudiobook/seekForwardAudiobook,
    // so we only need a non-null Context placeholder. mockk<Context>(relaxed = false)
    // instruments no methods — no SharedPreferences call is ever made.
    private val appContext: Context = mockk<Context>(relaxed = false)

    // Dispatchers.Unconfined is used as the Main dispatcher so that viewModelScope.launch
    // from inside a runTest block does not trigger the TestMainDispatcher re-entrancy guard
    // (which would infinite-loop on launches that suspend). Unconfined runs launches
    // synchronously on the calling thread, which is what these tests want.
    private val mainDispatcher = Dispatchers.Unconfined

    @BeforeEach
    fun setUp() {
        // The init coroutine completes before tests start collecting, so only the
        // final state is observable — no loading state in emissions.
        Dispatchers.setMain(mainDispatcher)
        coEvery { getItem(any()) }           returns fakeItem
        coEvery { getProgress(any()) }       returns fakeProgress
        coEvery { observeBookmarks(any()) }  returns flowOf(emptyList())
        coEvery { observeHighlights(any()) } returns flowOf(emptyList())
        // Settle the controller future so ReaderViewModel.init's addListener callback
        // can resolve controllerFuture.get() without blocking.
        controllerFuture.set(mockController)
        every { mockController.addListener(any()) } returns Unit
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
            savedStateHandle    = SavedStateHandle(mapOf("itemId" to itemId)),
            getItem             = getItem,
            getVaultFolder      = getVaultFolder,
            openFile            = openFile,
            getProgress         = getProgress,
            saveProgress        = saveProgress,
            observeBookmarks    = observeBookmarks,
            addBookmark         = addBookmark,
            deleteBookmark      = deleteBookmark,
            updateBookmarkNote  = updateBookmarkNote,
            observeHighlights   = observeHighlights,
            addHighlight        = addHighlight,
            deleteHighlight     = deleteHighlight,
            logger              = logger,
            playbackStateHolder = playbackStateHolder,
            controllerFuture    = controllerFuture,
            appContext          = appContext,
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
            savedStateHandle    = SavedStateHandle(mapOf("itemId" to 99L)),
            getItem             = getItem,
            getVaultFolder      = getVaultFolder,
            openFile            = openFile,
            getProgress         = getProgress,
            saveProgress        = saveProgress,
            observeBookmarks    = observeBookmarks,
            addBookmark         = addBookmark,
            deleteBookmark      = deleteBookmark,
            updateBookmarkNote  = updateBookmarkNote,
            observeHighlights   = observeHighlights,
            addHighlight        = addHighlight,
            deleteHighlight     = deleteHighlight,
            logger              = logger,
            playbackStateHolder = playbackStateHolder,
            controllerFuture    = controllerFuture,
            appContext          = appContext,
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

    @Test
    fun `markdown scroll change saves a fraction, not a pixel offset`() = runTest {
        // Regression coverage for #125 — this used to be an Int pixel offset
        // (onMarkdownScrollChanged(offset: Int)); a 0.0..1.0 Double fraction survives
        // font-size/theme/rotation changes between sessions the way a pixel value can't.
        val vm = viewModel()
        vm.onMarkdownScrollChanged(0.42)

        val saved = slot<ReadingProgress>()
        coVerify { saveProgress(capture(saved)) }
        assertEquals(0.42, saved.captured.markdownScrollFraction)
        assertEquals(1L, saved.captured.itemId)
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

    // ── Bookmarks sheet + note editing ─────────────────────────────────────────

    @Test
    fun `bookmarks sheet shows and hides`() = runTest {
        val vm = viewModel()
        assertFalse(vm.uiState.value.showBookmarksSheet)
        vm.showBookmarks()
        assertTrue(vm.uiState.value.showBookmarksSheet)
        vm.hideBookmarks()
        assertFalse(vm.uiState.value.showBookmarksSheet)
    }

    @Test
    fun `add bookmark sets lastAddedBookmarkId for toast`() = runTest {
        coEvery { addBookmark(any()) } returns 99L
        val vm = viewModel()
        vm.addBookmark("epubcfi(/6/4!/4/2/1:0)", "Loved this bit")
        // addBookmark launches in viewModelScope; UnconfinedTestDispatcher runs it eagerly,
        // so by the time we read the state the lastAddedBookmarkId should be populated.
        vm.uiState.test {
            val state = awaitItem()
            assertEquals(99L, state.lastAddedBookmarkId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `clearBookmarkToast resets lastAddedBookmarkId`() = runTest {
        coEvery { addBookmark(any()) } returns 99L
        val vm = viewModel()
        vm.addBookmark("epubcfi(/6/4!/4/2/1:0)")
        assertNotNull(vm.uiState.value.lastAddedBookmarkId)
        vm.clearBookmarkToast()
        assertNull(vm.uiState.value.lastAddedBookmarkId)
    }

    @Test
    fun `updateBookmarkNote delegates to use case`() = runTest {
        viewModel().updateBookmarkNote(42L, "Edited note")
        coVerify { updateBookmarkNote(42L, "Edited note") }
    }

    @Test
    fun `updateBookmarkNote accepts null to clear the note`() = runTest {
        viewModel().updateBookmarkNote(42L, null)
        coVerify { updateBookmarkNote(42L, null) }
    }

    // ── Audiobook mini-player controls ────────────────────────────────────────

    @Test
    fun `pauseAudiobook is no-op when no item is loaded`() = runTest {
        // Default PlaybackStateHolder state has itemId = null, so the holder.update
        // branch should be skipped entirely.
        val vm = viewModel()
        vm.pauseAudiobook()
        // If this didn't crash, we're good — the holder is still empty.
        assertFalse(playbackStateHolder.state.value.isActive)
    }

    @Test
    fun `pauseAudiobook updates holder when an item is loaded`() = runTest {
        playbackStateHolder.update(
            itemId        = 7L,
            vaultFolderId = 1L,
            filePath      = "content://vault/book.mp3",
            title         = "Test Audiobook",
            author        = "Test Author",
            coverArtPath  = null,
            isPlaying     = true,
        )
        val vm = viewModel()
        vm.pauseAudiobook()
        assertFalse(playbackStateHolder.state.value.isPlaying)
        assertEquals(7L, playbackStateHolder.state.value.itemId)
    }

    @Test
    fun `playPauseAudiobook flips isPlaying via controller when an item is loaded`() = runTest {
        playbackStateHolder.update(
            itemId = 7L, vaultFolderId = 1L, filePath = "content://vault/book.mp3",
            title = "T", author = "A", coverArtPath = null, isPlaying = true,
        )
        every { mockController.isPlaying } returns true
        val vm = viewModel()
        vm.playPauseAudiobook()
        // controller.pause() called because it was playing
        io.mockk.verify { mockController.pause() }
        // holder flipped to not-playing
        assertFalse(playbackStateHolder.state.value.isPlaying)
    }

    @Test
    fun `playPauseAudiobook plays via controller when paused`() = runTest {
        playbackStateHolder.update(
            itemId = 7L, vaultFolderId = 1L, filePath = "content://vault/book.mp3",
            title = "T", author = "A", coverArtPath = null, isPlaying = false,
        )
        every { mockController.isPlaying } returns false
        val vm = viewModel()
        vm.playPauseAudiobook()
        io.mockk.verify { mockController.play() }
        assertTrue(playbackStateHolder.state.value.isPlaying)
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
