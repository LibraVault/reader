package xyz.libravault.feature.reader

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
import xyz.libravault.core.domain.model.ContentSource
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
import xyz.libravault.core.tts.TtsEngine
import xyz.libravault.core.tts.TtsEngineProvider
import xyz.libravault.core.tts.TtsState
import xyz.libravault.core.tts.TtsStatus
import xyz.libravault.core.vaultstore.VaultBookmark
import xyz.libravault.core.vaultstore.VaultHighlight
import xyz.libravault.core.vaultstore.VaultManifestEntry
import xyz.libravault.core.vaultstore.VaultSessionManager
import xyz.libravault.core.vaultstore.VaultStore
import xyz.libravault.feature.player.service.PlaybackStateHolder
import xyz.libravault.feature.player.service.SleepTimerState
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
    private val sessionManager     = mockk<VaultSessionManager>()

    // PR #11: audiobook mini-player state relay + MediaController future.
    // Use a real PlaybackStateHolder (cheap, no required init) and a SettableFuture
    // pre-populated with a relaxed mock controller — matches the pattern in
    // feature/player/src/test/.../PlayerViewModelTest.kt.
    private val playbackStateHolder = PlaybackStateHolder()
    private val mockController      = mockk<MediaController>(relaxed = true)
    private val controllerFuture    = SettableFuture.create<MediaController>()

    // PR #11: skip-duration setting is read from SharedPreferences via this Context.
    // The unit tests in this file never call seekBackAudiobook/seekForwardAudiobook,
    // so that particular read never happens. #428: every test does now trigger a
    // SharedPreferences read on init (ReaderViewModel's initial theme) and
    // onThemeChanged tests trigger a write too (ReadingThemePreference) — both
    // stubbed below with a relaxed fake prefs/editor pair.
    private val sharedPrefs: SharedPreferences = mockk(relaxed = true)
    private val sharedPrefsEditor: SharedPreferences.Editor = mockk(relaxed = true)
    private val appContext: Context = mockk<Context>(relaxed = false)

    // #137 — Read Aloud. A relaxed fake TtsEngine (real state/completionEvent flows
    // so tests can drive and observe them) behind a mocked TtsEngineProvider, matching
    // the pattern already used in feature:settings' SettingsViewModelTest.
    private val fakeTtsEngine        = mockk<TtsEngine>(relaxed = true)
    private val ttsEngineStateFlow   = MutableStateFlow(TtsState())
    private val ttsCompletionEvent   = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val ttsStopEvent         = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val ttsEngineFlow        = MutableStateFlow(fakeTtsEngine)
    private val ttsEngineProvider    = mockk<TtsEngineProvider>()

    // Dispatchers.Unconfined is used as the Main dispatcher so that viewModelScope.launch
    // from inside a runTest block does not trigger the TestMainDispatcher re-entrancy guard
    // (which would infinite-loop on launches that suspend). Unconfined runs launches
    // synchronously on the calling thread, which is what these tests want.
    private val mainDispatcher = Dispatchers.Unconfined

    // Owns every ViewModel this test class creates so tearDown() can clear() them —
    // same fix as MarkdownReaderViewModelTest's (#554/#553). ReaderViewModel.init
    // launches several infinite viewModelScope.launch { flow.collect {} } blocks (Read
    // Aloud completion/stop-event and sleep-timer collectors, see advanceReadAloudElapsed's
    // doc comment above) that only Android's real onCleared() lifecycle ever cancels —
    // without this, every test would leak them, and a later exception on one gets
    // misattributed to whichever unrelated test runs next
    // (kotlinx.coroutines.test.UncaughtExceptionsBeforeTest). See #562.
    private val viewModelStore = ViewModelStore()

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

        every { ttsEngineProvider.engine }  returns ttsEngineFlow
        every { fakeTtsEngine.state }           returns ttsEngineStateFlow
        every { fakeTtsEngine.completionEvent } returns ttsCompletionEvent
        every { fakeTtsEngine.stopEvent }       returns ttsStopEvent

        // #428 — ReadingThemePreference.read/write's SharedPreferences plumbing.
        // No stored value by default, so read() falls back to its documented DARK.
        every { appContext.getSharedPreferences(any(), any()) } returns sharedPrefs
        every { sharedPrefs.getString(any(), any()) } returns null
        every { sharedPrefs.edit() } returns sharedPrefsEditor
        every { sharedPrefsEditor.putString(any(), any()) } returns sharedPrefsEditor
    }

    @AfterEach
    fun tearDown() {
        viewModelStore.clear()
        Dispatchers.resetMain()
    }

    // Owns a ViewModel constructed outside the viewModel() factory below (e.g. when a
    // test needs to stub mocks between construction and the factory's own defaults) so
    // tearDown() still clears it.
    private fun own(vm: ReaderViewModel): ReaderViewModel = vm.also { viewModelStore.put(it.toString(), it) }

    private fun viewModel(itemId: Long = 1L): ReaderViewModel {
        coEvery { getItem(itemId) }           returns fakeItem
        coEvery { getProgress(itemId) }       returns fakeProgress
        coEvery { observeBookmarks(itemId) }  returns flowOf(emptyList())
        coEvery { observeHighlights(itemId) } returns flowOf(emptyList())

        return own(
            ReaderViewModel(
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
                ttsEngineProvider   = ttsEngineProvider,
                sessionManager      = sessionManager,
                appContext          = appContext,
            )
        )
    }

    // #505 — vaultId/fileId nav args instead of itemId, same shape VaultReaderScreen
    // (feature:vault, deleted by #505) used to read off its own SavedStateHandle.
    private fun vaultViewModel(vaultId: String = "vault-1", fileIdHex: String = "aabbcc"): ReaderViewModel =
        own(
            ReaderViewModel(
                savedStateHandle    = SavedStateHandle(mapOf("vaultId" to vaultId, "fileId" to fileIdHex)),
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
                ttsEngineProvider   = ttsEngineProvider,
                sessionManager      = sessionManager,
                appContext          = appContext,
            )
        )

    // ── tearDown cancels leaked ViewModel coroutines (#562) ─────────────────────

    @Test
    fun `tearDown cancels a leaked ViewModel coroutine`() = runTest {
        val vm = viewModel()
        val leaked = vm.viewModelScope.launch { awaitCancellation() }

        tearDown()

        assertTrue(leaked.isCancelled)
    }

    // ── Init ─────────────────────────────────────────────────────────────────

    @Test
    fun `loads item and progress on init`() = runTest {
        // init coroutine completes synchronously — first emission is already loaded
        viewModel().uiState.test {
            val loaded = awaitItem()
            assertFalse(loaded.isLoading)
            assertNotNull(loaded.contentSource)
            assertEquals(ContentSource.RealFile(fakeItem.filePath), loaded.contentSource)
            assertEquals("Test Book", loaded.title)
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

        val vm = own(
            ReaderViewModel(
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
                ttsEngineProvider   = ttsEngineProvider,
                sessionManager      = sessionManager,
                appContext          = appContext,
            )
        )

        // init coroutine already completed — first emission is the error state
        vm.uiState.test {
            val error = awaitItem()
            assertNotNull(error.error)
            assertNull(error.contentSource)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Init (Encrypted Vault, #505) ─────────────────────────────────────────

    private val fakeVaultEntry = VaultManifestEntry(
        fileId            = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()),
        title             = "Vault Book",
        author            = "Vault Author",
        format            = "EPUB",
        sizeBytes         = 1024L,
        addedAtEpochMillis = 0L,
    )

    @Test
    fun `vault init resolves a ContentSource VaultEntry and seeds title-author-format`() = runTest {
        val store = mockk<VaultStore>()
        every { sessionManager.isUnlocked("vault-1") } returns true
        every { sessionManager.requireUnlocked("vault-1") } returns store
        coEvery { store.listEntries() } returns listOf(fakeVaultEntry)

        vaultViewModel(fileIdHex = "aabbcc").uiState.test {
            val loaded = awaitItem()
            assertFalse(loaded.isLoading)
            assertNull(loaded.error)
            assertEquals(ContentSource.VaultEntry("vault-1", "aabbcc", MediaFormat.EPUB), loaded.contentSource)
            assertEquals("Vault Book", loaded.title)
            assertEquals("Vault Author", loaded.author)
            assertEquals(MediaFormat.EPUB, loaded.format)
            // No relative-image resolution for encrypted vault Markdown (#442 v1 scope) —
            // structural for every VaultEntry, not just Markdown, per ReaderUiState's doc.
            assertNull(loaded.vaultTreeUri)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `vault init surfaces a locked vault as an error, not a crash`() = runTest {
        every { sessionManager.isUnlocked("vault-1") } returns false

        vaultViewModel().uiState.test {
            val state = awaitItem()
            assertFalse(state.isLoading)
            assertEquals("Vault is locked", state.error)
            assertNull(state.contentSource)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `vault init surfaces a missing entry as an error`() = runTest {
        val store = mockk<VaultStore>()
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
    fun `vault init rejects an audio entry, routing the user back to the player`() = runTest {
        val store = mockk<VaultStore>()
        every { sessionManager.isUnlocked("vault-1") } returns true
        every { sessionManager.requireUnlocked("vault-1") } returns store
        coEvery { store.listEntries() } returns listOf(fakeVaultEntry.copy(format = "MP3"))

        vaultViewModel().uiState.test {
            val state = awaitItem()
            assertEquals("This is an audio file — open it from the player instead", state.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `vault init surfaces an exception from a fresh openReader-adjacent call as an error, not a crash`() = runTest {
        // Regression coverage for the gap VaultReaderViewModel (feature:vault, deleted
        // by #505) had: its PDF/Markdown branches let this kind of failure escape
        // unhandled — only its EPUB branch caught anything.
        every { sessionManager.isUnlocked("vault-1") } returns true
        every { sessionManager.requireUnlocked("vault-1") } throws IllegalStateException("Vault vault-1 is not unlocked")

        vaultViewModel().uiState.test {
            val state = awaitItem()
            assertFalse(state.isLoading)
            assertNotNull(state.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Vault lock observation (#526, ported from the deleted VaultReaderViewModel) ──

    @Test
    fun `checkStillUnlocked flips wasLocked when the session manager reports the vault no longer unlocked`() = runTest {
        val store = mockk<VaultStore>()
        every { sessionManager.isUnlocked("vault-1") } returns true
        every { sessionManager.requireUnlocked("vault-1") } returns store
        coEvery { store.listEntries() } returns listOf(fakeVaultEntry)

        val vm = vaultViewModel()
        assertFalse(vm.uiState.value.wasLocked, "wasLocked must start false")

        // The vault locked out from under this screen (e.g. auto-lock firing while it
        // was backgrounded) -- sessionManager now reports it as locked.
        every { sessionManager.isUnlocked("vault-1") } returns false
        vm.checkStillUnlocked()

        assertTrue(vm.uiState.value.wasLocked, "checkStillUnlocked must flip wasLocked once the vault is no longer unlocked")
    }

    @Test
    fun `checkStillUnlocked does not flip wasLocked while still loading`() = runTest {
        // Guards the !isLoading check in checkStillUnlocked -- store.listEntries()
        // never returns (awaitCancellation), so init's coroutine genuinely suspends
        // mid-flight and isLoading stays true; this codebase's ViewModelTests use
        // Dispatchers.Unconfined (runs launched work eagerly up to the first real
        // suspension point), so without a real suspend point here init would just
        // run straight to completion and this guard couldn't be observed at all.
        val store = mockk<VaultStore>()
        every { sessionManager.isUnlocked("vault-1") } returns true
        every { sessionManager.requireUnlocked("vault-1") } returns store
        coEvery { store.listEntries() } coAnswers { kotlinx.coroutines.awaitCancellation() }

        val vm = vaultViewModel()
        assertTrue(vm.uiState.value.isLoading, "sanity: still loading")

        // isUnlocked=false would normally flip wasLocked, but must not while loading.
        every { sessionManager.isUnlocked("vault-1") } returns false
        vm.checkStillUnlocked()

        assertFalse(vm.uiState.value.wasLocked, "checkStillUnlocked must not flip wasLocked while state is still loading")
    }

    @Test
    fun `checkStillUnlocked is a no-op for a non-vault contentSource`() = runTest {
        val vm = viewModel() // plain itemId-based session, vaultRef == null
        vm.checkStillUnlocked() // must not throw (e.g. NPE on a null vaultRef)

        assertFalse(vm.uiState.value.wasLocked)
    }

    @Test
    fun `a vault mutating call that throws VaultLockedException flips wasLocked instead of crashing`() = runTest {
        val store = mockk<VaultStore>()
        every { sessionManager.isUnlocked("vault-1") } returns true
        every { sessionManager.requireUnlocked("vault-1") } returns store
        coEvery { store.listEntries() } returns listOf(fakeVaultEntry)
        coEvery { store.addBookmark(any(), "epubcfi(/6/4)", null, null) } throws
            xyz.libravault.core.vaultstore.VaultLockedException()

        val vm = vaultViewModel()
        assertFalse(vm.uiState.value.wasLocked, "wasLocked must start false")

        vm.addBookmark("epubcfi(/6/4)")

        assertTrue(vm.uiState.value.wasLocked, "launchOrNoticeLock must catch VaultLockedException and flip wasLocked")
        assertTrue(vm.bookmarks.value.isEmpty(), "a failed bookmark must not appear in state")
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
    fun `selecting OpenDyslexic font bumps line spacing to the accessibility default`() = runTest {
        val vm = viewModel()
        assertEquals(1.4f, vm.uiState.value.settings.lineSpacing) // sanity: default before selection

        vm.onFontFamilyChanged(FontFamily.OPEN_DYSLEXIC)

        assertEquals(FontFamily.OPEN_DYSLEXIC, vm.uiState.value.settings.fontFamily)
        assertEquals(DYSLEXIA_FRIENDLY_LINE_SPACING, vm.uiState.value.settings.lineSpacing)
    }

    @Test
    fun `selecting a non-accessibility font leaves line spacing untouched`() = runTest {
        val vm = viewModel()
        vm.onLineSpacingChanged(2.0f)

        vm.onFontFamilyChanged(FontFamily.SERIF)

        assertEquals(FontFamily.SERIF, vm.uiState.value.settings.fontFamily)
        assertEquals(2.0f, vm.uiState.value.settings.lineSpacing)
    }

    @Test
    fun `switching away from OpenDyslexic keeps the bumped line spacing until user changes it`() = runTest {
        val vm = viewModel()
        vm.onFontFamilyChanged(FontFamily.OPEN_DYSLEXIC)
        assertEquals(DYSLEXIA_FRIENDLY_LINE_SPACING, vm.uiState.value.settings.lineSpacing)

        // Switching to a different family doesn't force spacing back down —
        // only selecting OPEN_DYSLEXIC itself sets a value (see onFontFamilyChanged doc).
        vm.onFontFamilyChanged(FontFamily.SANS_SERIF)
        assertEquals(FontFamily.SANS_SERIF, vm.uiState.value.settings.fontFamily)
        assertEquals(DYSLEXIA_FRIENDLY_LINE_SPACING, vm.uiState.value.settings.lineSpacing)
    }

    @Test
    fun `warmth defaults to 0f`() = runTest {
        // #422 — session-only, same lifecycle as fontSize/lineSpacing.
        val vm = viewModel()
        assertEquals(0f, vm.uiState.value.settings.warmth)
    }

    @Test
    fun `onWarmthChanged clamps to the 0f to 1f range`() = runTest {
        // #422
        val vm = viewModel()

        vm.onWarmthChanged(5.0f)
        assertEquals(1.0f, vm.uiState.value.settings.warmth)

        vm.onWarmthChanged(-1.0f)
        assertEquals(0.0f, vm.uiState.value.settings.warmth)

        vm.onWarmthChanged(0.5f)
        assertEquals(0.5f, vm.uiState.value.settings.warmth)
    }

    @Test
    fun `onWarmthChanged updates only the warmth field`() = runTest {
        // #422
        val vm = viewModel()
        vm.onWarmthChanged(0.6f)

        assertEquals(0.6f, vm.uiState.value.settings.warmth)
        assertEquals(1.0f, vm.uiState.value.settings.fontSize)
    }

    @Test
    fun `auto-scroll speed is clamped to valid range`() = runTest {
        val vm = viewModel()
        vm.onAutoScrollSpeedChanged(10.0f)
        assertEquals(3.0f, vm.uiState.value.settings.autoScrollSpeed)

        vm.onAutoScrollSpeedChanged(0.1f)
        assertEquals(0.5f, vm.uiState.value.settings.autoScrollSpeed)
    }

    @Test
    fun `auto-scroll defaults to off and toggles independently of speed`() = runTest {
        val vm = viewModel()
        assertFalse(vm.uiState.value.settings.autoScrollEnabled)
        assertEquals(1.0f, vm.uiState.value.settings.autoScrollSpeed)

        vm.onAutoScrollEnabledChanged(true)
        assertTrue(vm.uiState.value.settings.autoScrollEnabled)
        assertEquals(1.0f, vm.uiState.value.settings.autoScrollSpeed)

        vm.onAutoScrollEnabledChanged(false)
        assertFalse(vm.uiState.value.settings.autoScrollEnabled)
    }

    @Test
    fun `theme change round-trips through ui state, including SYSTEM`() = runTest {
        val vm = viewModel()

        vm.onThemeChanged(xyz.libravault.core.ui.theme.ReadingTheme.SYSTEM)
        assertEquals(xyz.libravault.core.ui.theme.ReadingTheme.SYSTEM, vm.uiState.value.settings.theme)

        vm.onThemeChanged(xyz.libravault.core.ui.theme.ReadingTheme.SEPIA)
        assertEquals(xyz.libravault.core.ui.theme.ReadingTheme.SEPIA, vm.uiState.value.settings.theme)
    }

    @Test
    fun `initial theme is seeded from the persisted global default, not a hardcoded DARK`() = runTest {
        // Regression coverage for #428 — before this fix, ReaderUiState's settings
        // always started from ReaderSettings()'s own hardcoded DARK default,
        // regardless of what Settings has configured as defaultReadingTheme.
        every { sharedPrefs.getString(any(), any()) } returns "SEPIA"

        val vm = viewModel()

        assertEquals(xyz.libravault.core.ui.theme.ReadingTheme.SEPIA, vm.uiState.value.settings.theme)
    }

    @Test
    fun `onThemeChanged writes the new theme back to the persisted global default`() = runTest {
        // Regression coverage for #428 — before this fix, an in-reader theme change
        // lived only in the ViewModel's in-memory state and was lost on close.
        val vm = viewModel()

        vm.onThemeChanged(xyz.libravault.core.ui.theme.ReadingTheme.SEPIA)

        io.mockk.verify { sharedPrefsEditor.putString("reading_theme", "SEPIA") }
        io.mockk.verify { sharedPrefsEditor.apply() }
    }

    @Test
    fun `theme change round-trips through ui state for AMOLED`() = runTest {
        // #420
        val vm = viewModel()

        vm.onThemeChanged(xyz.libravault.core.ui.theme.ReadingTheme.AMOLED)
        assertEquals(xyz.libravault.core.ui.theme.ReadingTheme.AMOLED, vm.uiState.value.settings.theme)
    }

    // ── Margins/justification/hyphenation (#421) ────────────────────────────────

    @Test
    fun `margin scale is clamped to the 0_5 to 2_0 range`() = runTest {
        val vm = viewModel()
        vm.onMarginScaleChanged(5.0f)
        assertEquals(2.0f, vm.uiState.value.settings.marginScale)

        vm.onMarginScaleChanged(-1.0f)
        assertEquals(0.5f, vm.uiState.value.settings.marginScale)

        vm.onMarginScaleChanged(1.25f)
        assertEquals(1.25f, vm.uiState.value.settings.marginScale)
    }

    @Test
    fun `justify text round-trips through ui state`() = runTest {
        val vm = viewModel()
        assertFalse(vm.uiState.value.settings.justifyText)

        vm.onJustifyTextChanged(true)
        assertTrue(vm.uiState.value.settings.justifyText)

        vm.onJustifyTextChanged(false)
        assertFalse(vm.uiState.value.settings.justifyText)
    }

    @Test
    fun `hyphenation round-trips through ui state`() = runTest {
        val vm = viewModel()
        assertFalse(vm.uiState.value.settings.hyphenation)

        vm.onHyphenationChanged(true)
        assertTrue(vm.uiState.value.settings.hyphenation)

        vm.onHyphenationChanged(false)
        assertFalse(vm.uiState.value.settings.hyphenation)
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

    // ── Bookmarks/highlights (Encrypted Vault, #505) ─────────────────────────
    //
    // Round-trips through VaultStore instead of the Room use cases above —
    // ported from VaultReaderViewModel's (feature:vault, deleted by #505)
    // equivalent tests, now against ReaderViewModel's vaultRef branch.

    @Test
    fun `vault addBookmark round-trips through VaultStore and updates bookmarks state`() = runTest {
        val store = mockk<VaultStore>()
        every { sessionManager.isUnlocked("vault-1") } returns true
        every { sessionManager.requireUnlocked("vault-1") } returns store
        coEvery { store.listEntries() } returns listOf(fakeVaultEntry)
        val vaultBookmark = VaultBookmark(id = 1L, positionRef = "epubcfi(/6/4)", label = "My mark", note = null, createdAtEpochMillis = 0L)
        coEvery { store.addBookmark(any(), "epubcfi(/6/4)", "My mark", null) } returns vaultBookmark

        val vm = vaultViewModel()
        vm.addBookmark("epubcfi(/6/4)", "My mark")

        assertEquals(1, vm.bookmarks.value.size)
        assertEquals("epubcfi(/6/4)", vm.bookmarks.value.first().positionRef)
        assertEquals(1L, vm.uiState.value.lastAddedBookmarkId)
    }

    @Test
    fun `vault removeBookmark round-trips through VaultStore and updates bookmarks state`() = runTest {
        val store = mockk<VaultStore>()
        every { sessionManager.isUnlocked("vault-1") } returns true
        every { sessionManager.requireUnlocked("vault-1") } returns store
        coEvery { store.listEntries() } returns listOf(
            fakeVaultEntry.copy(
                bookmarks = listOf(VaultBookmark(id = 1L, positionRef = "epubcfi(/6/4)", label = null, note = null, createdAtEpochMillis = 0L)),
            ),
        )
        coEvery { store.removeBookmark(any(), 1L) } returns Unit

        val vm = vaultViewModel()
        assertEquals(1, vm.bookmarks.value.size) // seeded from the manifest entry

        vm.removeBookmark(1L)

        assertTrue(vm.bookmarks.value.isEmpty())
        coVerify { store.removeBookmark(any(), 1L) }
    }

    @Test
    fun `vault addHighlight round-trips through VaultStore and updates highlights state`() = runTest {
        val store = mockk<VaultStore>()
        every { sessionManager.isUnlocked("vault-1") } returns true
        every { sessionManager.requireUnlocked("vault-1") } returns store
        coEvery { store.listEntries() } returns listOf(fakeVaultEntry)
        val vaultHighlight = VaultHighlight(id = 1L, positionRef = "epubcfi(/6/4)", highlightedText = "text", colorHex = "#FFE066", note = null, createdAtEpochMillis = 0L)
        coEvery { store.addHighlight(any(), "epubcfi(/6/4)", "text", "#FFE066") } returns vaultHighlight

        val vm = vaultViewModel()
        vm.addHighlight("epubcfi(/6/4)", "text")

        assertEquals(1, vm.highlights.value.size)
        assertEquals("text", vm.highlights.value.first().highlightedText)
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

    // ── Read Aloud (#137) ────────────────────────────────────────────────────

    @Test
    fun `startReadAloud speaks the initial chapter text`() = runTest {
        val vm = viewModel()
        vm.startReadAloud(getInitialText = { "Chapter one." }, getNextText = { null })
        io.mockk.coVerify { fakeTtsEngine.speak("Chapter one.") }
    }

    @Test
    fun `startReadAloud stops instead of speaking when there is no text`() = runTest {
        val vm = viewModel()
        vm.startReadAloud(getInitialText = { null }, getNextText = { null })
        io.mockk.verify { fakeTtsEngine.stop() }
        io.mockk.coVerify(exactly = 0) { fakeTtsEngine.speak(any<String>()) }
    }

    @Test
    fun `startReadAloud pauses an already-playing audiobook`() = runTest {
        playbackStateHolder.update(
            itemId = 7L, vaultFolderId = 1L, filePath = "content://vault/book.mp3",
            title = "T", author = "A", coverArtPath = null, isPlaying = true,
        )
        every { mockController.isPlaying } returns true
        val vm = viewModel()
        vm.startReadAloud(getInitialText = { "Chapter one." }, getNextText = { null })
        io.mockk.verify { mockController.pause() }
        assertFalse(playbackStateHolder.state.value.isPlaying)
    }

    @Test
    fun `completion event advances to the next chapter`() = runTest {
        val vm = viewModel()
        vm.startReadAloud(getInitialText = { "Chapter one." }, getNextText = { "Chapter two." })
        ttsCompletionEvent.emit(Unit)
        io.mockk.coVerify { fakeTtsEngine.speak("Chapter two.") }
    }

    @Test
    fun `completion event stops at the end of the book`() = runTest {
        val vm = viewModel()
        vm.startReadAloud(getInitialText = { "Last chapter." }, getNextText = { null })
        ttsCompletionEvent.emit(Unit)
        io.mockk.verify { fakeTtsEngine.stop() }
    }

    @Test
    fun `completion event is a no-op when no Read Aloud session is active`() = runTest {
        viewModel()
        // No startReadAloud call — an unrelated completion (e.g. a voice preview
        // elsewhere sharing the singleton TtsEngineProvider) must not be misread
        // as "advance the book".
        ttsCompletionEvent.emit(Unit)
        io.mockk.coVerify(exactly = 0) { fakeTtsEngine.speak(any<String>()) }
    }

    @Test
    fun `pauseReadAloud and resumeReadAloud delegate to the engine`() = runTest {
        val vm = viewModel()
        vm.pauseReadAloud()
        io.mockk.verify { fakeTtsEngine.pause() }
        vm.resumeReadAloud()
        io.mockk.verify { fakeTtsEngine.resume() }
    }

    @Test
    fun `toggleReadAloudPlayPause pauses while playing and resumes while paused`() = runTest {
        val vm = viewModel()

        ttsEngineStateFlow.value = TtsState(status = TtsStatus.PLAYING)
        vm.toggleReadAloudPlayPause()
        io.mockk.verify { fakeTtsEngine.pause() }

        ttsEngineStateFlow.value = TtsState(status = TtsStatus.PAUSED)
        vm.toggleReadAloudPlayPause()
        io.mockk.verify { fakeTtsEngine.resume() }
    }

    @Test
    fun `stopReadAloud stops the engine and clears the next-chapter provider`() = runTest {
        val vm = viewModel()
        vm.startReadAloud(getInitialText = { "Chapter one." }, getNextText = { "Chapter two." })
        vm.stopReadAloud()
        io.mockk.verify { fakeTtsEngine.stop() }

        // With the provider cleared, a completion event that fires after stop() must
        // not resurrect the session by speaking the next chapter.
        ttsCompletionEvent.emit(Unit)
        io.mockk.coVerify(exactly = 0) { fakeTtsEngine.speak("Chapter two.") }
    }

    @Test
    fun `external stop via audio focus loss clears the next-chapter provider`() = runTest {
        // Regression coverage for #280: TtsAudioFocusManager calls engine.stop()
        // directly on focus loss (e.g. an audiobook resumed from the lockscreen),
        // bypassing stopReadAloud() entirely. Simulate that the way the real engines'
        // stop() actually behaves (AndroidTtsEngine.stop() / PocketTtsEngine.stop()):
        // state flips to IDLE *and* stopEvent fires - not via vm.stopReadAloud().
        val vm = viewModel()
        vm.startReadAloud(getInitialText = { "Chapter one." }, getNextText = { "Chapter two." })

        ttsEngineStateFlow.value = TtsState(status = TtsStatus.PLAYING)
        ttsEngineStateFlow.value = TtsState(status = TtsStatus.IDLE)
        ttsStopEvent.emit(Unit)

        // A stale provider would misread this later, unrelated completion event
        // (e.g. from a voice preview sharing the same singleton engine) as
        // "advance the book".
        ttsCompletionEvent.emit(Unit)
        io.mockk.coVerify(exactly = 0) { fakeTtsEngine.speak("Chapter two.") }
    }

    @Test
    fun `natural completion driven the way production engines actually sequence it still advances`() = runTest {
        // QA regression for #281: both AndroidTtsEngine.speakNext() and
        // PocketTtsEngine.speak()'s completion callback set `state` to IDLE
        // *before* emitting completionEvent from the very same call - i.e. every
        // natural chapter-to-chapter advance also produces a PLAYING -> IDLE edge
        // on `state`, not just an external stop. A fix that clears
        // readAloudNextChapterProvider off that edge (instead of stopEvent, which
        // natural completion never fires) breaks Read Aloud after the first
        // chapter. Drive state and the completion event in that same order here.
        val vm = viewModel()
        vm.startReadAloud(getInitialText = { "Chapter one." }, getNextText = { "Chapter two." })

        ttsEngineStateFlow.value = TtsState(status = TtsStatus.PLAYING)
        ttsEngineStateFlow.value = TtsState(status = TtsStatus.IDLE)
        ttsCompletionEvent.emit(Unit)

        io.mockk.coVerify { fakeTtsEngine.speak("Chapter two.") }
    }

    @Test
    fun `playPauseAudiobook stops an active Read Aloud session before playing`() = runTest {
        playbackStateHolder.update(
            itemId = 7L, vaultFolderId = 1L, filePath = "content://vault/book.mp3",
            title = "T", author = "A", coverArtPath = null, isPlaying = false,
        )
        every { mockController.isPlaying } returns false
        val vm = viewModel()
        vm.startReadAloud(getInitialText = { "Chapter one." }, getNextText = { null })

        vm.playPauseAudiobook()

        io.mockk.verify { fakeTtsEngine.stop() }
        io.mockk.verify { mockController.play() }
    }

    // ── Read Aloud Player screen (#138) ─────────────────────────────────────────

    @Test
    fun `showReadAloudPlayer and hideReadAloudPlayer round-trip through ui state`() = runTest {
        val vm = viewModel()
        assertFalse(vm.uiState.value.showReadAloudPlayer)
        vm.showReadAloudPlayer()
        assertTrue(vm.uiState.value.showReadAloudPlayer)
        vm.hideReadAloudPlayer()
        assertFalse(vm.uiState.value.showReadAloudPlayer)
    }

    @Test
    fun `startReadAloud seeds readAloudPlayback with a duration estimate and chapter info`() = runTest {
        val vm = viewModel()
        vm.startReadAloud(
            getInitialText  = { "one two three four five six seven eight nine ten" },
            getNextText     = { null },
            chapterIndex    = { 2 },
            chapterCount    = { 5 },
        )
        val playback = vm.readAloudPlayback.value
        assertEquals(0L, playback.elapsedMs)
        assertTrue(playback.durationMs > 0)
        assertEquals(2, playback.chapterIndex)
        assertEquals(5, playback.chapterCount)
    }

    @Test
    fun `stopReadAloud resets readAloudPlayback and closes the player overlay`() = runTest {
        val vm = viewModel()
        vm.startReadAloud(getInitialText = { "Chapter one." }, getNextText = { null })
        vm.showReadAloudPlayer()

        vm.stopReadAloud()

        assertEquals(ReadAloudPlaybackState(), vm.readAloudPlayback.value)
        assertFalse(vm.uiState.value.showReadAloudPlayer)
    }

    @Test
    fun `nextReadAloudChapter speaks the next chapter and updates chapter index`() = runTest {
        val vm = viewModel()
        vm.startReadAloud(
            getInitialText = { "Chapter one." },
            getNextText    = { "Chapter two." },
            chapterIndex   = { 0 },
            chapterCount   = { 2 },
        )
        vm.nextReadAloudChapter()
        io.mockk.coVerify { fakeTtsEngine.speak("Chapter two.") }
    }

    @Test
    fun `nextReadAloudChapter is a no-op at the end of the book`() = runTest {
        val vm = viewModel()
        vm.startReadAloud(getInitialText = { "Last chapter." }, getNextText = { null })
        vm.nextReadAloudChapter()
        // No stop, no crash — startReadAloud's initial speak is the only call.
        io.mockk.coVerify(exactly = 1) { fakeTtsEngine.speak(any<String>()) }
        io.mockk.verify(exactly = 0) { fakeTtsEngine.stop() }
    }

    @Test
    fun `previousReadAloudChapter speaks the previous chapter`() = runTest {
        val vm = viewModel()
        vm.startReadAloud(
            getInitialText  = { "Chapter two." },
            getNextText     = { null },
            getPreviousText = { "Chapter one." },
        )
        vm.previousReadAloudChapter()
        io.mockk.coVerify { fakeTtsEngine.speak("Chapter one.") }
    }

    @Test
    fun `previousReadAloudChapter is a no-op at the start of the book`() = runTest {
        val vm = viewModel()
        vm.startReadAloud(
            getInitialText  = { "Chapter one." },
            getNextText     = { null },
            getPreviousText = { null },
        )
        vm.previousReadAloudChapter()
        io.mockk.coVerify(exactly = 1) { fakeTtsEngine.speak(any<String>()) }
    }

    @Test
    fun `seekReadAloud clamps to the current duration estimate`() = runTest {
        val vm = viewModel()
        vm.startReadAloud(getInitialText = { "one two three four five" }, getNextText = { null })
        val duration = vm.readAloudPlayback.value.durationMs

        vm.seekReadAloud(duration + 999_999L)
        assertEquals(duration, vm.readAloudPlayback.value.elapsedMs)

        vm.seekReadAloud(-999_999L)
        assertEquals(0L, vm.readAloudPlayback.value.elapsedMs)
    }

    @Test
    fun `skipForwardReadAloud and skipBackwardReadAloud move elapsed by the given delta`() = runTest {
        val vm = viewModel()
        vm.startReadAloud(
            getInitialText = { (1..500).joinToString(" ") { "word" } }, // long chapter, big duration
            getNextText    = { null },
        )
        vm.seekReadAloud(10_000L)
        vm.skipForwardReadAloud(5_000L)
        assertEquals(15_000L, vm.readAloudPlayback.value.elapsedMs)

        vm.skipBackwardReadAloud(3_000L)
        assertEquals(12_000L, vm.readAloudPlayback.value.elapsedMs)
    }

    @Test
    fun `setReadAloudSpeed delegates to the engine and rescales duration proportionally`() = runTest {
        val vm = viewModel()
        vm.startReadAloud(
            getInitialText = { (1..500).joinToString(" ") { "word" } },
            getNextText    = { null },
        )
        val before = vm.readAloudPlayback.value
        vm.seekReadAloud(before.durationMs / 2)

        vm.setReadAloudSpeed(2.0f)

        io.mockk.verify { fakeTtsEngine.setSpeechRate(2.0f) }
        val after = vm.readAloudPlayback.value
        // Doubling speed halves the remaining/total duration; elapsed fraction preserved.
        assertTrue(kotlin.math.abs(after.durationMs - before.durationMs / 2) <= 1L)
        assertEquals(0.5f, after.elapsedMs.toFloat() / after.durationMs, 0.01f)
    }

    @Test
    fun `sleep timer sheet shows and hides`() = runTest {
        val vm = viewModel()
        assertFalse(vm.uiState.value.showReadAloudSleepTimerSheet)
        vm.showReadAloudSleepTimer()
        assertTrue(vm.uiState.value.showReadAloudSleepTimerSheet)
        vm.hideReadAloudSleepTimer()
        assertFalse(vm.uiState.value.showReadAloudSleepTimerSheet)
    }

    @Test
    fun `startReadAloudSleepTimer hides the sheet immediately`() = runTest {
        // The timer's actual countdown/fire behaviour (including that firing pauses
        // playback, with no volume fade) is covered directly and deterministically in
        // ReadAloudSleepTimerTest, using a scope this test doesn't have virtual-time
        // control over here (see mainDispatcher's doc above) — this only checks the
        // synchronous sheet-dismissal side effect.
        val vm = viewModel()
        vm.startReadAloud(getInitialText = { "Chapter one." }, getNextText = { null })
        vm.showReadAloudSleepTimer()

        vm.startReadAloudSleepTimer(60_000L)

        assertFalse(vm.uiState.value.showReadAloudSleepTimerSheet)
        vm.cancelReadAloudSleepTimer() // Don't leave a real countdown running past this test.
    }

    @Test
    fun `cancelReadAloudSleepTimer is safe when no timer is active`() = runTest {
        val vm = viewModel()
        vm.startReadAloud(getInitialText = { "Chapter one." }, getNextText = { null })
        vm.cancelReadAloudSleepTimer() // Should not throw.
        assertEquals(SleepTimerState.Inactive, vm.readAloudPlayback.value.sleepTimerState)
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
