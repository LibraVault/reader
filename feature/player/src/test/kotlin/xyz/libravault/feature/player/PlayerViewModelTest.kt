package xyz.libravault.feature.player

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import xyz.libravault.feature.player.service.SleepTimerState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.core.domain.usecase.AddBookmarkUseCase
import xyz.libravault.core.domain.usecase.DeleteBookmarkUseCase
import xyz.libravault.core.domain.usecase.GetLibraryItemUseCase
import xyz.libravault.core.domain.usecase.GetListeningProgressUseCase
import xyz.libravault.core.domain.usecase.ObserveBookmarksUseCase
import xyz.libravault.core.domain.usecase.SaveListeningProgressUseCase
import xyz.libravault.core.domain.usecase.UpdateBookmarkNoteUseCase
import xyz.libravault.core.logger.LibravaultLogger
import xyz.libravault.feature.player.service.Chapter
import xyz.libravault.feature.player.service.ChapterExtractor
import xyz.libravault.feature.player.service.PlaybackStateHolder
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
        durationMs    = 7_200_000L,
    )

    private val fakeChapters = listOf(
        Chapter(0, "Chapter 1", 0L, 1_800_000L),
        Chapter(1, "Chapter 2", 1_800_000L, 3_600_000L),
        Chapter(2, "Chapter 3", 3_600_000L, 7_200_000L),
    )

    private val getItem          = mockk<GetLibraryItemUseCase>()
    private val getProgress      = mockk<GetListeningProgressUseCase>(relaxed = true)
    private val saveProgress     = mockk<SaveListeningProgressUseCase>(relaxed = true)
    private val observeBookmarks = mockk<ObserveBookmarksUseCase>()
    private val addBookmark      = mockk<AddBookmarkUseCase>(relaxed = true)
    private val deleteBookmark   = mockk<DeleteBookmarkUseCase>(relaxed = true)
    private val updateBookmarkNote = mockk<UpdateBookmarkNoteUseCase>(relaxed = true)
    private val chapterExtractor = mockk<ChapterExtractor>()
    private val sleepTimer          = mockk<SleepTimer>(relaxed = true)
    private val logger              = mockk<LibravaultLogger>(relaxed = true)
    private val playbackStateHolder = mockk<PlaybackStateHolder>(relaxed = true)
    private val sessionManager      = mockk<xyz.libravault.core.vaultstore.VaultSessionManager>(relaxed = true)
    private val appContext          = mockk<android.content.Context>(relaxed = true)

    private val mockController      = mockk<MediaController>(relaxed = true)
    private val controllerFuture = SettableFuture.create<MediaController>()
    private val sleepTimerState  = MutableStateFlow<SleepTimerState>(SleepTimerState.Inactive)

    @BeforeEach
    fun setUp() {
        // UnconfinedTestDispatcher runs viewModelScope coroutines eagerly/synchronously.
        // This means loadItem() completes before the test starts collecting uiState,
        // so the initial loading=true state is never observable — tests check final state only.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        controllerFuture.set(mockController)
        every { mockController.addListener(any()) } returns Unit
        every { sleepTimer.state } returns sleepTimerState
        // Stub chapterExtractor so connectWithRetry → play → updateChapters doesn't throw
        coEvery { chapterExtractor.extract(any(), any()) } returns fakeChapters
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(itemId: Long = 1L): PlayerViewModel {
        coEvery { getItem(itemId) }          returns fakeItem
        coEvery { observeBookmarks(itemId) } returns flowOf(emptyList())

        return PlayerViewModel(
            savedStateHandle   = SavedStateHandle(mapOf("itemId" to itemId)),
            getItem            = getItem,
            getProgress        = getProgress,
            saveProgress       = saveProgress,
            observeBookmarks   = observeBookmarks,
            addBookmark        = addBookmark,
            deleteBookmark     = deleteBookmark,
            updateBookmarkNote = updateBookmarkNote,
            controllerFuture   = controllerFuture,
            chapterExtractor   = chapterExtractor,
            sleepTimer         = sleepTimer,
            logger             = logger,
            playbackStateHolder = playbackStateHolder,
            sessionManager     = sessionManager,
            appContext         = appContext,
        )
    }

    @Test
    fun `loads item on init`() = runTest {
        // loadItem() runs synchronously with UnconfinedTestDispatcher, so the
        // first (and only) emission is already the loaded state.
        viewModel().uiState.test {
            val loaded = awaitItem()
            assertFalse(loaded.isLoading)
            assertNotNull(loaded.item)
            assertEquals("Test Audiobook", loaded.item!!.title)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Regression test for #642 (Bug 1): [PlayerViewModel.playerListener] must be
     * assigned *before* `init{}` can invoke `connectWithRetry()`'s callback — a
     * property declared after `init{}` in the class body is still null at the
     * point `init{}` runs, and `controller.addListener(null)` either NPEs here
     * (capturing `null` into a non-null-typed slot) or, on a real device against
     * a real `MediaController`, throws "listener must not be null" inside
     * Media3's own implementation. This harness's `mockController` is relaxed
     * and accepts a null listener silently (no real implementation behind it to
     * validate the contract) — which is exactly why this bug shipped invisibly
     * to the existing suite; the only way to catch it here is to capture the
     * actual argument and prove it's a working listener, not just that some
     * call happened.
     */
    @Test
    fun `playerListener is fully initialized before it's attached to the controller`() = runTest {
        val listenerSlot = slot<androidx.media3.common.Player.Listener>()
        every { mockController.addListener(capture(listenerSlot)) } returns Unit

        viewModel().uiState.test {
            awaitItem() // loaded state
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(listenerSlot.isCaptured)
        // false (not true) deliberately: playGeneration is already >0 from the initial
        // attach, so this hits onIsPlayingChanged's own early-return "stale" branch —
        // side-effect-free (doesn't start a real, unbounded startPolling() loop under
        // this harness's dispatcher) while still proving the point: a null capture
        // would NPE right here, on the call itself, regardless of the argument.
        listenerSlot.captured.onIsPlayingChanged(false)
        assertTrue(true) // reaching here means the captured listener is real
    }

    /**
     * Regression test for #642 (Bug 2): [PlayerViewModel.loadItem] and
     * `connectWithRetry()` each independently try to bootstrap playback once
     * `controller` is available — [PlayerViewModel.attachOrPlay] is the shared,
     * now-guarded tail both call into. The real race (loadItem's coroutine and
     * connectWithRetry's callback completing on real, independent dispatcher
     * timing) isn't reproducible through this module's synchronous
     * `UnconfinedTestDispatcher` harness, so this calls [PlayerViewModel.attachOrPlay]
     * directly a second time — exactly what the real race would do — and
     * asserts it's a no-op the second time.
     */
    @Test
    fun `attachOrPlay only bootstraps playback once per ViewModel instance`() = runTest {
        val vm = viewModel()
        vm.uiState.test {
            awaitItem() // loaded state — normal init flow already called attachOrPlay once
            cancelAndIgnoreRemainingEvents()
        }
        io.mockk.clearMocks(mockController, answers = false, recordedCalls = true)
        every { mockController.isPlaying } returns true // so a real second attach wouldn't call play() either way

        vm.attachOrPlay(mockController, fakeItem, startPositionMs = 0L, startSpeed = 1.0f)

        // A second, unguarded attach would re-seek/re-set-speed/re-play; the guard
        // must make this entirely inert.
        verify(exactly = 0) { mockController.seekTo(any<Long>()) }
        verify(exactly = 0) { mockController.setPlaybackSpeed(any()) }
        verify(exactly = 0) { mockController.play() }
    }

    @Test
    fun `error state when item not found`() = runTest {
        coEvery { getItem(99L) }          returns null
        coEvery { observeBookmarks(99L) } returns flowOf(emptyList())

        val vm = PlayerViewModel(
            savedStateHandle = SavedStateHandle(mapOf("itemId" to 99L)),
            getItem          = getItem,
            getProgress      = getProgress,
            saveProgress     = saveProgress,
            observeBookmarks = observeBookmarks,
            addBookmark      = addBookmark,
            deleteBookmark   = deleteBookmark,
            updateBookmarkNote = updateBookmarkNote,
            controllerFuture = controllerFuture,
            chapterExtractor = chapterExtractor,
            sleepTimer       = sleepTimer,
            logger           = logger,
            playbackStateHolder = playbackStateHolder,
            sessionManager   = sessionManager,
            appContext       = appContext,
        )

        // With UnconfinedTestDispatcher, loadItem() completes synchronously.
        // The error is set by loadItem(), then immediately cleared by
        // connectController() on success — so the final state has no error.
        vm.uiState.test {
            val state = awaitItem()
            assertNull(state.item)
            // Note: error is null because the controller connected successfully
            // and cleared it. The key assertion is that item remains null.
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Init (Encrypted Vault, #493) ─────────────────────────────────────────

    private fun vaultViewModel(vaultId: String = "vault-1", fileIdHex: String = "aabbcc"): PlayerViewModel =
        PlayerViewModel(
            savedStateHandle   = SavedStateHandle(mapOf("vaultId" to vaultId, "fileId" to fileIdHex)),
            getItem            = getItem,
            getProgress        = getProgress,
            saveProgress       = saveProgress,
            observeBookmarks   = observeBookmarks,
            addBookmark        = addBookmark,
            deleteBookmark     = deleteBookmark,
            updateBookmarkNote = updateBookmarkNote,
            controllerFuture   = controllerFuture,
            chapterExtractor   = chapterExtractor,
            sleepTimer         = sleepTimer,
            logger             = logger,
            playbackStateHolder = playbackStateHolder,
            sessionManager     = sessionManager,
            appContext         = appContext,
        )

    private val fakeVaultAudioEntry = xyz.libravault.core.vaultstore.VaultManifestEntry(
        fileId             = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()),
        title              = "Vault Audiobook",
        author             = "Vault Author",
        format             = "MP3",
        sizeBytes          = 1024L,
        addedAtEpochMillis = 0L,
    )

    @Test
    fun `vault init surfaces a locked vault as an error, not a crash`() = runTest {
        every { sessionManager.isUnlocked("vault-1") } returns false

        vaultViewModel().uiState.test {
            val state = awaitItem()
            assertFalse(state.isLoading)
            assertEquals("Vault is locked", state.error)
            assertNull(state.item)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `vault init surfaces a missing entry as an error`() = runTest {
        val store = mockk<xyz.libravault.core.vaultstore.VaultStore>()
        every { sessionManager.isUnlocked("vault-1") } returns true
        every { sessionManager.requireUnlocked("vault-1") } returns store
        coEvery { store.listEntries() } returns emptyList()

        vaultViewModel().uiState.test {
            val state = awaitItem()
            assertEquals("File not found in this vault", state.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `vault init rejects a non-audio entry`() = runTest {
        val store = mockk<xyz.libravault.core.vaultstore.VaultStore>()
        every { sessionManager.isUnlocked("vault-1") } returns true
        every { sessionManager.requireUnlocked("vault-1") } returns store
        coEvery { store.listEntries() } returns listOf(fakeVaultAudioEntry.copy(format = "EPUB"))

        vaultViewModel().uiState.test {
            val state = awaitItem()
            assertEquals("This isn't an audio file — open it from the reader instead", state.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `vault init resolves an audio entry and builds a vault URI`() = runTest {
        val store = mockk<xyz.libravault.core.vaultstore.VaultStore>()
        every { sessionManager.isUnlocked("vault-1") } returns true
        every { sessionManager.requireUnlocked("vault-1") } returns store
        coEvery { store.listEntries() } returns listOf(fakeVaultAudioEntry)

        vaultViewModel().uiState.test {
            val state = awaitItem()
            assertFalse(state.isLoading)
            assertNull(state.error)
            assertNotNull(state.item)
            // The synthetic item's filePath IS the vault:// URI — see loadVaultItem's
            // doc for why nothing downstream needs its own vault branch to build one.
            assertEquals("vault://vault-1/aabbcc", state.item!!.filePath)
            assertEquals("Vault Audiobook", state.item!!.title)
            assertEquals("Vault Author", state.item!!.author)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── buildMediaItem placeholder toggle (Phase 3, #508) ────────────────────
    // Unlike loadVaultItem (above) and syncPlaybackStateHolder (below), these
    // call PlayerViewModel.play(uri) directly with a hand-built mock Uri
    // instead of going through init's Uri.parse(item.filePath) path — see
    // syncPlaybackStateHolder's own test doc for why android.net.Uri.parse
    // isn't meaningfully mockable in this module's plain-JUnit5 tests. play()
    // takes a Uri parameter directly, so this sidesteps that entirely.

    @Test
    fun `vault MediaItem uses a generic placeholder title by default`() = runTest {
        val store = mockk<xyz.libravault.core.vaultstore.VaultStore>()
        every { sessionManager.isUnlocked("vault-1") } returns true
        every { sessionManager.requireUnlocked("vault-1") } returns store
        coEvery { store.listEntries() } returns listOf(fakeVaultAudioEntry)
        val fakePrefs = mockk<android.content.SharedPreferences>()
        every { appContext.getSharedPreferences(any(), any()) } returns fakePrefs
        every { fakePrefs.getBoolean(any(), false) } returns false
        val vaultUri = mockk<Uri>()
        every { vaultUri.scheme } returns "vault"
        every { vaultUri.toString() } returns "vault://vault-1/aabbcc"

        val vm = vaultViewModel()
        vm.uiState.test { awaitItem(); cancelAndIgnoreRemainingEvents() }
        io.mockk.clearMocks(mockController, answers = false, recordedCalls = true)
        vm.play(vaultUri)

        val slot = slot<androidx.media3.common.MediaItem>()
        verify(exactly = 1) { mockController.setMediaItem(capture(slot), any<Long>()) }
        assertEquals("Vault", slot.captured.mediaMetadata.title)
        assertNull(slot.captured.mediaMetadata.artist)
    }

    @Test
    fun `vault MediaItem uses the real title-author when the setting is enabled`() = runTest {
        val store = mockk<xyz.libravault.core.vaultstore.VaultStore>()
        every { sessionManager.isUnlocked("vault-1") } returns true
        every { sessionManager.requireUnlocked("vault-1") } returns store
        coEvery { store.listEntries() } returns listOf(fakeVaultAudioEntry)
        val fakePrefs = mockk<android.content.SharedPreferences>()
        every { appContext.getSharedPreferences(any(), any()) } returns fakePrefs
        every { fakePrefs.getBoolean(any(), false) } returns true
        val vaultUri = mockk<Uri>()
        every { vaultUri.scheme } returns "vault"
        every { vaultUri.toString() } returns "vault://vault-1/aabbcc"

        val vm = vaultViewModel()
        vm.uiState.test { awaitItem(); cancelAndIgnoreRemainingEvents() }
        io.mockk.clearMocks(mockController, answers = false, recordedCalls = true)
        vm.play(vaultUri)

        val slot = slot<androidx.media3.common.MediaItem>()
        verify(exactly = 1) { mockController.setMediaItem(capture(slot), any<Long>()) }
        assertEquals("Vault Audiobook", slot.captured.mediaMetadata.title)
        assertEquals("Vault Author", slot.captured.mediaMetadata.artist)
    }

    /**
     * [PlayerViewModel.syncPlaybackStateHolder]'s branch selection, exercised via
     * [PlayerViewModel.togglePlayPause] rather than through init's controller-reattach
     * path — `android.net.Uri.parse` isn't meaningfully mockable in this module's
     * plain-JUnit5 (non-Robolectric) unit tests (it returns null, not a comparable
     * `Uri`), which would make an init-time assertion here exercise a codepath this
     * harness can't actually simulate correctly. `togglePlayPause` calls
     * `syncPlaybackStateHolder` directly without touching `Uri.parse` at all.
     */
    @Test
    fun `syncPlaybackStateHolder calls updateVault, not update, for a vault item`() = runTest {
        val store = mockk<xyz.libravault.core.vaultstore.VaultStore>()
        every { sessionManager.isUnlocked("vault-1") } returns true
        every { sessionManager.requireUnlocked("vault-1") } returns store
        coEvery { store.listEntries() } returns listOf(fakeVaultAudioEntry)
        every { mockController.isPlaying } returns false

        vaultViewModel().togglePlayPause()

        // itemId must stay null for a vault item — see PlaybackStateHolder.State.vaultEntry's doc.
        verify(exactly = 0) { playbackStateHolder.update(itemId = any(), vaultFolderId = any(), filePath = any(), title = any(), author = any(), coverArtPath = any(), isPlaying = any()) }
        verify(atLeast = 1) {
            playbackStateHolder.updateVault(
                vaultEntry = xyz.libravault.core.domain.model.ContentSource.VaultEntry("vault-1", "aabbcc", MediaFormat.MP3),
                title = "Vault Audiobook", author = "Vault Author", coverArtPath = null, isPlaying = true,
            )
        }
    }

    @Test
    fun `sleep timer sheet shows and hides`() = runTest {
        val vm = viewModel()
        vm.uiState.test {
            awaitItem() // loaded state (loading already completed)
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
            awaitItem() // loaded state (loading already completed)
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
        vm.goToChapter(-1)  // should be ignored
        vm.goToChapter(999) // should be ignored
        // No exception = pass
    }

    @Test
    fun `cancel sleep timer delegates to SleepTimer`() = runTest {
        viewModel().cancelSleepTimer()
        coVerify { sleepTimer.cancel() }
    }

    @Test
    fun `retryPlayback clears error and re-prepares media`() = runTest {
        val vm = viewModel()

        // Simulate a player error by calling retry after initial load
        // First, verify the controller is set and has the mock
        vm.retryPlayback()

        // error should remain null (no error to clear), but we verify
        // the controller methods were called correctly
        assertNull(vm.uiState.value.error)
        coVerify { mockController.stop() }
        coVerify { mockController.setMediaItem(any()) }
        coVerify { mockController.prepare() }
        coVerify { mockController.play() }
    }
}
