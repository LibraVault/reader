package xyz.libravault.feature.player

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import xyz.libravault.feature.player.service.SleepTimerState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.core.domain.usecase.AddBookmarkUseCase
import xyz.libravault.core.domain.usecase.GetLibraryItemUseCase
import xyz.libravault.core.domain.usecase.ObserveBookmarksUseCase
import xyz.libravault.core.domain.usecase.SaveListeningProgressUseCase
import xyz.libravault.core.logger.LibravaultLogger
import xyz.libravault.feature.player.service.Chapter
import xyz.libravault.feature.player.service.ChapterExtractor
import xyz.libravault.feature.player.service.SleepTimer
import com.google.common.util.concurrent.SettableFuture
import androidx.media3.session.MediaController

class PlayerViewModelTest {

    private val fakeItem = LibraryItem(
        id            = 1L,
        vaultFolderId = 1L,
        filePath      = "content://test/audiobook.m4b",
        title         = "Test Audiobook",
        author        = "Test Author",
        format        = MediaFormat.M4B,
        durationMs    = 7_200_000L, // 2 hours
    )

    private val fakeChapters = listOf(
        Chapter(0, "Chapter 1", 0L, 1_800_000L),
        Chapter(1, "Chapter 2", 1_800_000L, 3_600_000L),
        Chapter(2, "Chapter 3", 3_600_000L, 7_200_000L),
    )

    private val getItem           = mockk<GetLibraryItemUseCase>()
    private val saveProgress      = mockk<SaveListeningProgressUseCase>(relaxed = true)
    private val observeBookmarks  = mockk<ObserveBookmarksUseCase>()
    private val addBookmark       = mockk<AddBookmarkUseCase>(relaxed = true)
    private val chapterExtractor  = mockk<ChapterExtractor>()
    private val sleepTimer        = mockk<SleepTimer>(relaxed = true)
    private val logger            = mockk<LibravaultLogger>(relaxed = true)

    // MediaController future — completed with mock to avoid blocking in tests
    private val mockController = mockk<MediaController>(relaxed = true)
    private val controllerFuture: SettableFuture<MediaController> = SettableFuture.create()
    
    init {
        controllerFuture.set(mockController)
        every { mockController.addListener(any()) } returns Unit
    }

    private fun viewModel(itemId: Long = 1L): PlayerViewModel {
        coEvery { getItem(itemId) }          returns fakeItem
        coEvery { observeBookmarks(itemId) } returns flowOf(emptyList())
        every { sleepTimer.state }           returns MutableStateFlow<SleepTimerState>(
            SleepTimerState.Inactive
        )
        every { mockController.addListener(any()) } returns Unit

        return PlayerViewModel(
            savedStateHandle  = SavedStateHandle(mapOf("itemId" to itemId)),
            getItem           = getItem,
            saveProgress      = saveProgress,
            observeBookmarks  = observeBookmarks,
            addBookmark       = addBookmark,
            controllerFuture  = controllerFuture,
            chapterExtractor  = chapterExtractor,
            sleepTimer        = sleepTimer,
            logger            = logger,
        )
    }

    @Test
    fun `loads item on init`() = runTest {
        advanceUntilIdle()
        val vm = viewModel()
        advanceUntilIdle()
        vm.uiState.test {
            val loading = awaitItem()
            assertTrue(loading.isLoading)

            val loaded = awaitItem()
            assertFalse(loaded.isLoading)
            assertNotNull(loaded.item)
            assertEquals("Test Audiobook", loaded.item!!.title)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `error state when item not found`() = runTest {
        coEvery { getItem(99L) }          returns null
        coEvery { observeBookmarks(99L) } returns flowOf(emptyList())

        val vm = PlayerViewModel(
            savedStateHandle = SavedStateHandle(mapOf("itemId" to 99L)),
            getItem          = getItem,
            saveProgress     = saveProgress,
            observeBookmarks = observeBookmarks,
            addBookmark      = addBookmark,
            controllerFuture = controllerFuture,
            chapterExtractor = chapterExtractor,
            sleepTimer       = sleepTimer,
            logger           = logger,
        )

        vm.uiState.test {
            awaitItem() // loading
            val error = awaitItem()
            assertNotNull(error.error)
            assertNull(error.item)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sleep timer sheet shows and hides`() = runTest {
        val vm = viewModel()
        vm.uiState.test {
            awaitItem(); awaitItem() // skip loading
            assertFalse(vm.uiState.value.showSleepTimerSheet)
            vm.showSleepTimer()
            assertTrue(awaitItem().showSleepTimerSheet)
            vm.hideSleepTimer()
            assertFalse(awaitItem().showSleepTimerSheet)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `bookmarks sheet shows and hides`() = runTest {
        val vm = viewModel()
        vm.uiState.test {
            awaitItem(); awaitItem()
            vm.showBookmarks()
            assertTrue(awaitItem().showBookmarksSheet)
            vm.hideBookmarks()
            assertFalse(awaitItem().showBookmarksSheet)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `chapter navigation clamps to valid range`() = runTest {
        val vm = viewModel()
        // goToChapter with out-of-range index should not throw
        vm.uiState.value // trigger init
        vm.goToChapter(-1)  // should be ignored
        vm.goToChapter(999) // should be ignored
        // No exception = pass
    }

    @Test
    fun `cancel sleep timer delegates to SleepTimer`() = runTest {
        viewModel().cancelSleepTimer()
        coVerify { sleepTimer.cancel() }
    }
}
