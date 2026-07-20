package xyz.libravault.feature.library

import android.net.Uri
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.core.domain.model.VaultFolder
import xyz.libravault.core.domain.scanner.ScanProgress
import xyz.libravault.core.domain.usecase.AddVaultFolderUseCase
import xyz.libravault.core.domain.usecase.DeleteBookmarkUseCase
import xyz.libravault.core.domain.usecase.GetLibraryUseCase
import xyz.libravault.core.domain.usecase.ObserveAllBookmarksUseCase
import xyz.libravault.core.domain.usecase.ObserveCurrentlyReadingUseCase
import xyz.libravault.core.domain.usecase.ObserveVaultsUseCase
import xyz.libravault.core.domain.usecase.RemoveVaultFolderUseCase
import xyz.libravault.core.domain.usecase.ScanVaultUseCase
import xyz.libravault.core.domain.usecase.SearchLibraryUseCase
import xyz.libravault.core.logger.LibravaultLogger
import xyz.libravault.core.storage.SupporterRepository
import xyz.libravault.core.storage.VaultManager
import xyz.libravault.feature.player.service.PlaybackStateHolder
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.SettableFuture

class LibraryViewModelTest {

    private val fakeVaults = listOf(
        VaultFolder(id = 1L, uri = "content://vault1", displayName = "Vault 1"),
        VaultFolder(id = 2L, uri = "content://vault2", displayName = "Vault 2"),
    )

    private val fakeItems = listOf(
        LibraryItem(id = 1L, vaultFolderId = 1L, filePath = "file1.epub", title = "Book 1", author = "Author A", format = MediaFormat.EPUB, durationMs = 0L),
        LibraryItem(id = 2L, vaultFolderId = 1L, filePath = "file2.m4b", title = "Audio 1", author = "Narrator B", format = MediaFormat.M4B, durationMs = 3_600_000L),
        LibraryItem(id = 3L, vaultFolderId = 2L, filePath = "file3.pdf", title = "Book 2", author = "Author C", format = MediaFormat.PDF, durationMs = 0L),
    )

    private val observeVaults         = mockk<ObserveVaultsUseCase>()
    private val getLibrary            = mockk<GetLibraryUseCase>()
    private val observeCurrentlyReading = mockk<ObserveCurrentlyReadingUseCase>()
    private val scanVault             = mockk<ScanVaultUseCase>()
    private val searchLibrary         = mockk<SearchLibraryUseCase>()
    private val addVaultFolder        = mockk<AddVaultFolderUseCase>()
    private val removeVaultFolder     = mockk<RemoveVaultFolderUseCase>()
    private val vaultManager          = mockk<VaultManager>()
    private val logger                = mockk<LibravaultLogger>(relaxed = true)
    private val playbackStateHolder   = mockk<PlaybackStateHolder>()
    private val observeAllBookmarks   = mockk<ObserveAllBookmarksUseCase>()
    private val deleteBookmark        = mockk<DeleteBookmarkUseCase>(relaxed = true)
    private val supporterRepository   = mockk<SupporterRepository>()

    private val mockController        = mockk<MediaController>(relaxed = true)
    private val controllerFuture      = SettableFuture.create<MediaController>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        controllerFuture.set(mockController)
        every { mockController.addListener(any()) } returns Unit
        every { playbackStateHolder.state } returns MutableStateFlow(PlaybackStateHolder.State())
        every { supporterRepository.observe() } returns flowOf(false)
        every { supporterRepository.isSupporter() } returns false
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): LibraryViewModel {
        coEvery { observeVaults() } returns flowOf(fakeVaults)
        coEvery { getLibrary() } returns flowOf(fakeItems)
        coEvery { observeCurrentlyReading.reading() } returns flowOf(emptyList())
        coEvery { observeCurrentlyReading.listening() } returns flowOf(emptyList())
        coEvery { observeAllBookmarks() } returns flowOf(emptyList())
        coEvery { scanVault() } returns flowOf(ScanProgress.Completed(3, null))
        coEvery { vaultManager.persistedVaultUris() } returns emptyList()

        return LibraryViewModel(
            observeVaults         = observeVaults,
            getLibrary            = getLibrary,
            observeCurrentlyReading = observeCurrentlyReading,
            scanVault             = scanVault,
            searchLibrary         = searchLibrary,
            addVaultFolder        = addVaultFolder,
            removeVaultFolder     = removeVaultFolder,
            vaultManager          = vaultManager,
            logger                = logger,
            playbackStateHolder   = playbackStateHolder,
            observeAllBookmarks   = observeAllBookmarks,
            deleteBookmark        = deleteBookmark,
            controllerFuture      = controllerFuture,
            supporterRepository   = supporterRepository,
        )
    }

    // ── Format filter ────────────────────────────────────────────────────────

    @Test
    fun `formatFilteredItems filters by AUDIO`() {
        val items = fakeItems
        val filtered = LibraryViewModel().formatFilteredItems(items, "AUDIO")
        assertEquals(1, filtered.size)
        assertEquals(2L, filtered[0].id)
        assertTrue(filtered[0].format.isAudio())
    }

    @Test
    fun `formatFilteredItems filters by BOOK`() {
        val items = fakeItems
        val filtered = LibraryViewModel().formatFilteredItems(items, "BOOK")
        assertEquals(2, filtered.size)
        assertTrue(filtered.all { !it.format.isAudio() })
    }

    @Test
    fun `formatFilteredItems returns all when format is null`() {
        val items = fakeItems
        val filtered = LibraryViewModel().formatFilteredItems(items, null)
        assertEquals(items.size, filtered.size)
    }

    @Test
    fun `vaultFilteredItems returns empty for null selectedVaultId`() {
        val grouped = fakeItems.groupBy { fakeVaults.find { v -> v.id == it.vaultFolderId } ?: fakeVaults[0] }
        val result = LibraryViewModel().vaultFilteredItems(grouped, null)
        assertEquals(0, result.size)
    }

    @Test
    fun `vaultFilteredItems returns items for matching vault`() {
        val grouped = fakeItems.groupBy { fakeVaults.find { v -> v.id == it.vaultFolderId } ?: fakeVaults[0] }
        val result = LibraryViewModel().vaultFilteredItems(grouped, 1L)
        assertEquals(2, result.size)
        assertTrue(result.all { it.vaultFolderId == 1L })
    }

    // ── Search and scanning ──────────────────────────────────────────────────

    @Test
    fun `onSearchQueryChanged with blank query clears results immediately`() = runTest {
        val vm = viewModel()
        vm.onSearchQueryChanged("   ")
        assertEquals("   ", vm.uiState.value.searchQuery)
        assertNull(vm.uiState.value.searchResults)
    }

    @Test
    fun `onSearchQueryChanged debounces and calls searchLibrary`() = runTest {
        coEvery { searchLibrary("test") } returns listOf(fakeItems[0])
        val vm = viewModel()

        vm.onSearchQueryChanged("test")
        assertEquals("test", vm.uiState.value.searchQuery)
        kotlinx.coroutines.delay(350)
        coVerify { searchLibrary("test") }
    }

    @Test
    fun `clearSearch resets query and results`() = runTest {
        val vm = viewModel()
        vm.onSearchQueryChanged("query")
        vm.clearSearch()
        assertEquals("", vm.uiState.value.searchQuery)
        assertNull(vm.uiState.value.searchResults)
    }

    @Test
    fun `triggerScan sets scanning flag and clears error`() = runTest {
        coEvery { scanVault() } returns flowOf(ScanProgress.Completed(1, null))
        val vm = viewModel()
        vm.uiState.test {
            awaitItem() // initial state
            val scanning = awaitItem()
            assertTrue(scanning.isScanning)
            val final = awaitItem()
            assertFalse(final.isScanning)
            assertNull(final.scanError)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `triggerScan records scan errors`() = runTest {
        coEvery { scanVault() } returns flowOf(ScanProgress.Error("Scan failed"))
        val vm = viewModel()
        vm.uiState.test {
            awaitItem() // initial
            awaitItem() // scanning
            val error = awaitItem()
            assertEquals("Scan failed", error.scanError)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Vault management ─────────────────────────────────────────────────────

    @Test
    fun `onVaultPicked persists permission and adds vault`() = runTest {
        coEvery { addVaultFolder("content://test", "TestVault") } returns VaultFolder(99L, "content://test", "TestVault")
        val vm = viewModel()

        vm.onVaultPicked(Uri.parse("content://test"), "TestVault")

        coVerify { vaultManager.persistPermission(Uri.parse("content://test")) }
        coVerify { addVaultFolder("content://test", "TestVault") }
        coVerify(atLeast = 1) { scanVault() }
    }

    @Test
    fun `removeVault releases permission and removes from Room`() = runTest {
        val vault = fakeVaults[0]
        val vm = viewModel()

        vm.removeVault(vault)

        coVerify { vaultManager.releasePermission(Uri.parse(vault.uri)) }
        coVerify { removeVaultFolder(vault.id) }
    }

    @Test
    fun `removeVault clears filter if removing selected vault`() = runTest {
        val vault = fakeVaults[0]
        val vm = viewModel()

        vm.selectVault(vault.id)
        assertEquals(vault.id, vm.uiState.value.selectedVault?.id)

        vm.removeVault(vault)

        assertNull(vm.uiState.value.selectedVault)
    }

    // ── Mini-player controls ─────────────────────────────────────────────────

    @Test
    fun `playPause toggles controller`() = runTest {
        every { mockController.isPlaying } returns false
        val vm = viewModel()

        vm.playPause()

        coVerify { mockController.play() }
    }

    @Test
    fun `seekBack delegates to controller seekTo`() = runTest {
        every { mockController.currentPosition } returns 60_000L
        every { mockController.duration } returns 3_600_000L
        val vm = viewModel()

        vm.seekBack()

        coVerify { mockController.seekTo(any()) }
    }

    @Test
    fun `seekForward delegates to controller seekTo`() = runTest {
        every { mockController.currentPosition } returns 60_000L
        every { mockController.duration } returns 3_600_000L
        val vm = viewModel()

        vm.seekForward()

        coVerify { mockController.seekTo(any()) }
    }

    // ── Bookmark management ──────────────────────────────────────────────────

    @Test
    fun `onDeleteBookmark calls deleteBookmark use case`() = runTest {
        val vm = viewModel()

        vm.onDeleteBookmark(123L)

        coVerify { deleteBookmark(123L) }
    }

    // ── Vault filter ─────────────────────────────────────────────────────────

    @Test
    fun `selectVault updates selected vault id`() {
        val vm = viewModel()
        vm.selectVault(2L)
        assertEquals(2L, vm.uiState.value.selectedVault?.id)
    }

    @Test
    fun `clearVaultFilter resets selected vault`() {
        val vm = viewModel()
        vm.selectVault(1L)
        vm.clearVaultFilter()
        assertNull(vm.uiState.value.selectedVault)
    }

    // ── Format filter ────────────────────────────────────────────────────────

    @Test
    fun `onFormatFilterChanged sets format filter`() {
        val vm = viewModel()
        vm.onFormatFilterChanged("EPUB")
        assertEquals("EPUB", vm.uiState.value.formatFilter)
    }

    @Test
    fun `clearFormatFilter resets filter to null`() {
        val vm = viewModel()
        vm.onFormatFilterChanged("PDF")
        vm.clearFormatFilter()
        assertNull(vm.uiState.value.formatFilter)
    }
}
