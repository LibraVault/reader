package xyz.libravault.feature.library

import app.cash.turbine.test
import com.google.common.util.concurrent.ListenableFuture
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.core.domain.model.VaultFolder
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

/**
 * Unit tests for [LibraryViewModel] — focused on the state-merge logic that's
 * easy to get subtly wrong: format filtering and the "Continue" shelf's
 * reading/listening interleave (see [InterleaveByRecencyTest] for the pure
 * merge function itself; this exercises it end-to-end through [uiState]).
 */
class LibraryViewModelTest {

    private val observeVaults = mockk<ObserveVaultsUseCase>()
    private val getLibrary = mockk<GetLibraryUseCase>()
    private val observeCurrentlyReading = mockk<ObserveCurrentlyReadingUseCase>()
    private val scanVault = mockk<ScanVaultUseCase>()
    private val searchLibrary = mockk<SearchLibraryUseCase>()
    private val addVaultFolder = mockk<AddVaultFolderUseCase>(relaxed = true)
    private val removeVaultFolder = mockk<RemoveVaultFolderUseCase>(relaxed = true)
    private val vaultManager = mockk<VaultManager>(relaxed = true)
    private val logger = mockk<LibravaultLogger>(relaxed = true)
    private val playbackStateHolder = PlaybackStateHolder()
    private val observeAllBookmarks = mockk<ObserveAllBookmarksUseCase>()
    private val deleteBookmark = mockk<DeleteBookmarkUseCase>(relaxed = true)
    private val controllerFuture = mockk<ListenableFuture<androidx.media3.session.MediaController>>(relaxed = true)
    private val supporterRepository = mockk<SupporterRepository>(relaxed = true)

    private fun item(id: Long, vaultId: Long = 1L, format: MediaFormat = MediaFormat.EPUB) = LibraryItem(
        id = id,
        vaultFolderId = vaultId,
        filePath = "/book$id",
        title = "Book $id",
        author = "Author",
        format = format,
    )

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        // removeVault() calls the real android.net.Uri.parse(); unmocked in
        // @AfterEach to avoid leaking the class redefinition across tests
        // (see SettingsViewModelTest's tearDown for why that matters).
        mockkStatic(android.net.Uri::class)
        every { android.net.Uri.parse(any()) } returns mockk(relaxed = true)

        // Defaults so init{}'s vault-recovery + auto-scan path is a no-op
        // unless a specific test overrides observeVaults()/persistedVaultUris().
        every { observeVaults() } returns flowOf(emptyList())
        every { vaultManager.persistedVaultUris() } returns emptyList()
        every { scanVault() } returns emptyFlow()
        every { getLibrary() } returns flowOf(emptyList())
        every { observeCurrentlyReading.reading(any()) } returns flowOf(emptyList())
        every { observeCurrentlyReading.listening(any()) } returns flowOf(emptyList())
        every { observeAllBookmarks() } returns flowOf(emptyList())
        every { supporterRepository.observe() } returns flowOf(false)
        every { supporterRepository.isSupporter() } returns false
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(android.net.Uri::class)
        Dispatchers.resetMain()
    }

    private fun viewModel() = LibraryViewModel(
        observeVaults = observeVaults,
        getLibrary = getLibrary,
        observeCurrentlyReading = observeCurrentlyReading,
        scanVault = scanVault,
        searchLibrary = searchLibrary,
        addVaultFolder = addVaultFolder,
        removeVaultFolder = removeVaultFolder,
        vaultManager = vaultManager,
        logger = logger,
        playbackStateHolder = playbackStateHolder,
        observeAllBookmarks = observeAllBookmarks,
        deleteBookmark = deleteBookmark,
        controllerFuture = controllerFuture,
        supporterRepository = supporterRepository,
        appContext = mockk(relaxed = true),
    )

    // ── Format filter ─────────────────────────────────────────────────────────

    @Test
    fun `format filter AUDIO keeps only audio items`() = runTest(mainDispatcher) {
        val items = listOf(
            item(1, format = MediaFormat.EPUB),
            item(2, format = MediaFormat.MP3),
            item(3, format = MediaFormat.M4B),
        )
        every { getLibrary() } returns flowOf(items)

        val vm = viewModel()
        vm.onFormatFilterChanged("AUDIO")

        vm.uiState.test {
            val state = awaitItem()
            assertEquals(listOf(2L, 3L), state.allItems.filter { it.format.isAudio() }.map { it.id })
            assertEquals("AUDIO", state.formatFilter)
        }
    }

    @Test
    fun `format filter BOOK keeps only non-audio items`() = runTest(mainDispatcher) {
        val items = listOf(
            item(1, format = MediaFormat.EPUB),
            item(2, format = MediaFormat.PDF),
            item(3, format = MediaFormat.MP3),
        )
        every { getLibrary() } returns flowOf(items)

        val vm = viewModel()
        vm.onFormatFilterChanged("BOOK")

        vm.uiState.test {
            val state = awaitItem()
            assertEquals(listOf(1L, 2L), state.vaultGroupedItems.values.flatten().map { it.id }.sorted())
        }
    }

    @Test
    fun `clearing the format filter restores all items`() = runTest(mainDispatcher) {
        val items = listOf(item(1, format = MediaFormat.EPUB), item(2, format = MediaFormat.MP3))
        every { getLibrary() } returns flowOf(items)

        val vm = viewModel()
        vm.onFormatFilterChanged("AUDIO")
        vm.clearFormatFilter()

        vm.uiState.test {
            val state = awaitItem()
            assertEquals(null, state.formatFilter)
            assertEquals(2, state.vaultGroupedItems.values.flatten().size)
        }
    }

    @Test
    fun `format filter MARKDOWN keeps only markdown items`() = runTest(mainDispatcher) {
        val items = listOf(
            item(1, format = MediaFormat.EPUB),
            item(2, format = MediaFormat.MARKDOWN),
            item(3, format = MediaFormat.MP3),
        )
        every { getLibrary() } returns flowOf(items)

        val vm = viewModel()
        vm.onFormatFilterChanged(MediaFormat.MARKDOWN.name)

        vm.uiState.test {
            val state = awaitItem()
            assertEquals(listOf(2L), state.vaultGroupedItems.values.flatten().map { it.id })
            assertEquals("MARKDOWN", state.formatFilter)
        }
    }

    // ── formatFilteredItems / vaultFilteredItems (pure helpers) ─────────────────

    @Test
    fun `formatFilteredItems keeps only markdown for the MARKDOWN filter`() {
        val items = listOf(
            item(1, format = MediaFormat.MARKDOWN),
            item(2, format = MediaFormat.EPUB),
        )
        val vm = viewModel()

        assertEquals(listOf(1L), vm.formatFilteredItems(items, "MARKDOWN").map { it.id })
    }

    @Test
    fun `formatFilteredItems returns items unchanged for a null filter`() {
        val items = listOf(item(1, format = MediaFormat.EPUB), item(2, format = MediaFormat.MP3))
        val vm = viewModel()

        assertEquals(items, vm.formatFilteredItems(items, null))
    }

    @Test
    fun `vaultFilteredItems returns empty list when no vault is selected`() {
        val vm = viewModel()
        val grouped = mapOf(VaultFolder(id = 1, uri = "x", displayName = "V") to listOf(item(1)))

        assertEquals(emptyList<LibraryItem>(), vm.vaultFilteredItems(grouped, null))
    }

    // ── Continue shelf interleaving (end-to-end through uiState) ────────────────

    @Test
    fun `continueItems interleaves reading and listening from the use case flows`() = runTest(mainDispatcher) {
        val reading = listOf(item(1), item(2))
        val listening = listOf(item(10), item(20))
        every { observeCurrentlyReading.reading(any()) } returns flowOf(reading)
        every { observeCurrentlyReading.listening(any()) } returns flowOf(listening)

        val vm = viewModel()

        vm.uiState.test {
            val state = awaitItem()
            assertEquals(listOf(1L, 10L, 2L, 20L), state.continueItems.map { it.id })
        }
    }

    // ── Vault management delegation ──────────────────────────────────────────

    @Test
    fun `onVaultPicked persists permission and delegates to the use case`() = runTest(mainDispatcher) {
        val uri = mockk<android.net.Uri>(relaxed = true)
        every { uri.toString() } returns "content://vault/new"
        coEvery { addVaultFolder("content://vault/new", "My Vault") } returns
            VaultFolder(id = 1, uri = "content://vault/new", displayName = "My Vault")

        val vm = viewModel()
        vm.onVaultPicked(uri, "My Vault")

        verify { vaultManager.persistPermission(uri) }
        coVerify { addVaultFolder("content://vault/new", "My Vault") }
    }

    @Test
    fun `removeVault releases permission and delegates to the use case`() = runTest(mainDispatcher) {
        val vault = VaultFolder(id = 5, uri = "content://vault/5", displayName = "Old Vault")

        val vm = viewModel()
        vm.removeVault(vault)

        verify { vaultManager.releasePermission(any()) }
        coVerify { removeVaultFolder(5) }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    @Test
    fun `clearSearch resets query and results`() = runTest(mainDispatcher) {
        val vm = viewModel()
        vm.onSearchQueryChanged("harry")
        vm.clearSearch()

        vm.uiState.test {
            val state = awaitItem()
            assertEquals("", state.searchQuery)
            assertEquals(null, state.searchResults)
        }
    }

    @Test
    fun `blank search query clears results without calling the use case`() = runTest(mainDispatcher) {
        val vm = viewModel()
        vm.onSearchQueryChanged("   ")

        vm.uiState.test {
            val state = awaitItem()
            assertEquals(null, state.searchResults)
        }
        coVerify(exactly = 0) { searchLibrary(any()) }
    }

    // ── Mini-player (#493) ───────────────────────────────────────────────────
    // playPause() needs a resolved MediaController, unlike every other test above —
    // the shared controllerFuture mock never invokes its addListener callback, so
    // these build their own ViewModel with a real SettableFuture, same pattern
    // PlayerViewModelTest uses.

    private fun viewModelWithConnectedController(
        mockController: androidx.media3.session.MediaController,
    ): LibraryViewModel {
        val future = com.google.common.util.concurrent.SettableFuture.create<androidx.media3.session.MediaController>()
        future.set(mockController)
        return LibraryViewModel(
            observeVaults = observeVaults,
            getLibrary = getLibrary,
            observeCurrentlyReading = observeCurrentlyReading,
            scanVault = scanVault,
            searchLibrary = searchLibrary,
            addVaultFolder = addVaultFolder,
            removeVaultFolder = removeVaultFolder,
            vaultManager = vaultManager,
            logger = logger,
            playbackStateHolder = playbackStateHolder,
            observeAllBookmarks = observeAllBookmarks,
            deleteBookmark = deleteBookmark,
            controllerFuture = future,
            supporterRepository = supporterRepository,
            appContext = mockk(relaxed = true),
        )
    }

    /** #493 — a vault-sourced PlaybackStateHolder.State leaves itemId null by
     *  design; playPause() must branch on vaultEntry first or the mini-player's
     *  play/pause icon would silently never flip for vault audio. */
    @Test
    fun `playPause flips a vault item via updateVault, not update`() = runTest(mainDispatcher) {
        val mockController = mockk<androidx.media3.session.MediaController>(relaxed = true)
        every { mockController.isPlaying } returns false
        playbackStateHolder.updateVault(
            vaultEntry = xyz.libravault.core.domain.model.ContentSource.VaultEntry("vault-1", "aabbcc", MediaFormat.MP3),
            title = "Vault Audiobook", author = "Vault Author", coverArtPath = null, isPlaying = false,
        )

        viewModelWithConnectedController(mockController).playPause()

        val state = playbackStateHolder.state.value
        assertEquals(xyz.libravault.core.domain.model.ContentSource.VaultEntry("vault-1", "aabbcc", MediaFormat.MP3), state.vaultEntry)
        assertEquals(null, state.itemId)
        assertEquals(true, state.isPlaying)
    }

    @Test
    fun `playPause flips a real-file item via update`() = runTest(mainDispatcher) {
        val mockController = mockk<androidx.media3.session.MediaController>(relaxed = true)
        every { mockController.isPlaying } returns false
        playbackStateHolder.update(
            itemId = 1L, vaultFolderId = 1L, filePath = "content://x",
            title = "Book", author = "Author", coverArtPath = null, isPlaying = false,
        )

        viewModelWithConnectedController(mockController).playPause()

        val state = playbackStateHolder.state.value
        assertEquals(1L, state.itemId)
        assertEquals(null, state.vaultEntry)
        assertEquals(true, state.isPlaying)
    }
}
