package xyz.libravault.feature.vault

import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import io.mockk.coVerify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.readium.r2.shared.publication.Publication
import xyz.libravault.core.vaultcrypto.VaultFileReader
import xyz.libravault.core.vaultstore.VaultBookmark
import xyz.libravault.core.vaultstore.VaultHighlight
import xyz.libravault.core.vaultstore.VaultManifestEntry
import xyz.libravault.core.vaultstore.VaultSessionManager
import xyz.libravault.core.vaultstore.VaultStore

class VaultReaderViewModelTest {

    private val sessionManager = mockk<VaultSessionManager>()
    private val readiumProvider = mockk<VaultReadiumProvider>()
    private val vaultStore = mockk<VaultStore>()

    private val fileId = ByteArray(16) { it.toByte() }
    private val fileIdHex = fileId.toHexString()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { sessionManager.requireUnlocked("vault-1") } returns vaultStore
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = VaultReaderViewModel(
        sessionManager, readiumProvider,
        SavedStateHandle(mapOf("vaultId" to "vault-1", "fileId" to fileIdHex)),
    )

    private fun entry(format: String) = VaultManifestEntry(
        fileId = fileId, title = "Title", author = "Author", format = format,
        sizeBytes = 100L, addedAtEpochMillis = 0L,
    )

    @Test
    fun `locked vault surfaces an Error state without listing entries`() = runTest {
        every { sessionManager.isUnlocked("vault-1") } returns false

        val vm = viewModel()
        advanceUntilIdle()

        assertInstanceOf(VaultReaderState.Error::class.java, vm.state.value)
    }

    @Test
    fun `unknown fileId surfaces an Error state`() = runTest {
        every { sessionManager.isUnlocked("vault-1") } returns true
        coEvery { vaultStore.listEntries() } returns emptyList()

        val vm = viewModel()
        advanceUntilIdle()

        assertInstanceOf(VaultReaderState.Error::class.java, vm.state.value)
    }

    @Test
    fun `an audio entry routes to WrongScreen instead of opening a reader`() = runTest {
        every { sessionManager.isUnlocked("vault-1") } returns true
        coEvery { vaultStore.listEntries() } returns listOf(entry("MP3"))

        val vm = viewModel()
        advanceUntilIdle()

        assertInstanceOf(VaultReaderState.WrongScreen::class.java, vm.state.value)
    }

    @Test
    fun `a PDF entry opens a reader and reaches PdfReady`() = runTest {
        every { sessionManager.isUnlocked("vault-1") } returns true
        coEvery { vaultStore.listEntries() } returns listOf(entry("PDF"))
        every { vaultStore.openReader(fileId) } returns mockk<VaultFileReader>(relaxed = true)

        val vm = viewModel()
        advanceUntilIdle()

        val state = vm.state.value
        assertInstanceOf(VaultReaderState.PdfReady::class.java, state)
        assertEquals("Title", (state as VaultReaderState.PdfReady).title)
    }

    @Test
    fun `an EPUB entry that fails to open surfaces the failure message`() = runTest {
        every { sessionManager.isUnlocked("vault-1") } returns true
        coEvery { vaultStore.listEntries() } returns listOf(entry("EPUB"))
        every { vaultStore.openReader(fileId) } returns mockk<VaultFileReader>(relaxed = true)
        coEvery { readiumProvider.open(any(), fileIdHex) } returns Result.failure(Exception("corrupt EPUB"))

        val vm = viewModel()
        advanceUntilIdle()

        val state = vm.state.value
        assertInstanceOf(VaultReaderState.Error::class.java, state)
        assertEquals("corrupt EPUB", (state as VaultReaderState.Error).message)
    }

    @Test
    fun `an EPUB entry that opens successfully reaches EpubReady`() = runTest {
        every { sessionManager.isUnlocked("vault-1") } returns true
        coEvery { vaultStore.listEntries() } returns listOf(entry("EPUB"))
        every { vaultStore.openReader(fileId) } returns mockk<VaultFileReader>(relaxed = true)
        val publication = mockk<Publication>(relaxed = true)
        coEvery { readiumProvider.open(any(), fileIdHex) } returns Result.success(publication)

        val vm = viewModel()
        advanceUntilIdle()

        val state = vm.state.value
        assertInstanceOf(VaultReaderState.EpubReady::class.java, state)
        assertEquals("Title", (state as VaultReaderState.EpubReady).title)
    }

    @Test
    fun `an unsupported format surfaces an Error state`() = runTest {
        every { sessionManager.isUnlocked("vault-1") } returns true
        coEvery { vaultStore.listEntries() } returns listOf(entry("MARKDOWN"))
        every { vaultStore.openReader(fileId) } returns mockk<VaultFileReader>(relaxed = true)

        val vm = viewModel()
        advanceUntilIdle()

        assertInstanceOf(VaultReaderState.Error::class.java, vm.state.value)
    }

    // ── Bookmarks & highlights ───────────────────────────────────────────────

    @Test
    fun `existing bookmarks and highlights from the manifest are exposed on load`() = runTest {
        val bookmark = VaultBookmark(id = 1L, positionRef = "page:2", createdAtEpochMillis = 0L)
        val highlight = VaultHighlight(id = 1L, positionRef = "ref", highlightedText = "text", createdAtEpochMillis = 0L)
        every { sessionManager.isUnlocked("vault-1") } returns true
        coEvery { vaultStore.listEntries() } returns listOf(
            entry("PDF").copy(bookmarks = listOf(bookmark), highlights = listOf(highlight)),
        )
        every { vaultStore.openReader(fileId) } returns mockk<VaultFileReader>(relaxed = true)

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(listOf(bookmark), vm.bookmarks.value)
        assertEquals(listOf(highlight), vm.highlights.value)
    }

    @Test
    fun `addBookmark is a no-op until a position has been reported`() = runTest {
        every { sessionManager.isUnlocked("vault-1") } returns true
        coEvery { vaultStore.listEntries() } returns listOf(entry("PDF"))
        every { vaultStore.openReader(fileId) } returns mockk<VaultFileReader>(relaxed = true)

        val vm = viewModel()
        advanceUntilIdle()
        vm.addBookmark()
        advanceUntilIdle()

        assertTrue(vm.bookmarks.value.isEmpty())
        coVerify(exactly = 0) { vaultStore.addBookmark(any(), any(), any(), any()) }
    }

    @Test
    fun `addBookmark bookmarks the last reported PDF page and appends to state`() = runTest {
        every { sessionManager.isUnlocked("vault-1") } returns true
        coEvery { vaultStore.listEntries() } returns listOf(entry("PDF"))
        every { vaultStore.openReader(fileId) } returns mockk<VaultFileReader>(relaxed = true)
        val bookmark = VaultBookmark(id = 1L, positionRef = "page:4", label = "note", createdAtEpochMillis = 0L)
        coEvery { vaultStore.addBookmark(fileId, "page:4", "note", null) } returns bookmark

        val vm = viewModel()
        advanceUntilIdle()
        vm.onPdfPageChanged(4)
        vm.addBookmark(label = "note")
        advanceUntilIdle()

        assertEquals(listOf(bookmark), vm.bookmarks.value)
    }

    @Test
    fun `onEpubPositionChanged feeds addBookmark's positionRef directly, no page prefix`() = runTest {
        every { sessionManager.isUnlocked("vault-1") } returns true
        coEvery { vaultStore.listEntries() } returns listOf(entry("EPUB"))
        every { vaultStore.openReader(fileId) } returns mockk<VaultFileReader>(relaxed = true)
        coEvery { readiumProvider.open(any(), fileIdHex) } returns Result.success(mockk(relaxed = true))
        coEvery { vaultStore.addBookmark(fileId, "{\"locator\":true}", null, null) } returns
            VaultBookmark(id = 1L, positionRef = "{\"locator\":true}", createdAtEpochMillis = 0L)

        val vm = viewModel()
        advanceUntilIdle()
        vm.onEpubPositionChanged("{\"locator\":true}")
        vm.addBookmark()
        advanceUntilIdle()

        coVerify(exactly = 1) { vaultStore.addBookmark(fileId, "{\"locator\":true}", null, null) }
    }

    @Test
    fun `removeBookmark removes exactly the targeted bookmark from state`() = runTest {
        val kept = VaultBookmark(id = 1L, positionRef = "page:1", createdAtEpochMillis = 0L)
        val removed = VaultBookmark(id = 2L, positionRef = "page:2", createdAtEpochMillis = 0L)
        every { sessionManager.isUnlocked("vault-1") } returns true
        coEvery { vaultStore.listEntries() } returns listOf(entry("PDF").copy(bookmarks = listOf(kept, removed)))
        every { vaultStore.openReader(fileId) } returns mockk<VaultFileReader>(relaxed = true)
        coEvery { vaultStore.removeBookmark(fileId, 2L) } returns Unit

        val vm = viewModel()
        advanceUntilIdle()
        vm.removeBookmark(2L)
        advanceUntilIdle()

        assertEquals(listOf(kept), vm.bookmarks.value)
    }

    @Test
    fun `updateBookmarkNote updates exactly the targeted bookmark in state`() = runTest {
        val bookmark = VaultBookmark(id = 1L, positionRef = "page:1", note = "old", createdAtEpochMillis = 0L)
        every { sessionManager.isUnlocked("vault-1") } returns true
        coEvery { vaultStore.listEntries() } returns listOf(entry("PDF").copy(bookmarks = listOf(bookmark)))
        every { vaultStore.openReader(fileId) } returns mockk<VaultFileReader>(relaxed = true)
        coEvery { vaultStore.updateBookmarkNote(fileId, 1L, "new") } returns Unit

        val vm = viewModel()
        advanceUntilIdle()
        vm.updateBookmarkNote(1L, "new")
        advanceUntilIdle()

        assertEquals("new", vm.bookmarks.value.single().note)
    }

    @Test
    fun `addHighlight appends the store's returned highlight to state`() = runTest {
        every { sessionManager.isUnlocked("vault-1") } returns true
        coEvery { vaultStore.listEntries() } returns listOf(entry("EPUB"))
        every { vaultStore.openReader(fileId) } returns mockk<VaultFileReader>(relaxed = true)
        coEvery { readiumProvider.open(any(), fileIdHex) } returns Result.success(mockk(relaxed = true))
        val highlight = VaultHighlight(id = 1L, positionRef = "ref", highlightedText = "text", createdAtEpochMillis = 0L)
        coEvery { vaultStore.addHighlight(fileId, "ref", "text", "#FFE066") } returns highlight

        val vm = viewModel()
        advanceUntilIdle()
        vm.addHighlight("ref", "text")
        advanceUntilIdle()

        assertEquals(listOf(highlight), vm.highlights.value)
    }

    @Test
    fun `removeHighlight removes exactly the targeted highlight from state`() = runTest {
        val kept = VaultHighlight(id = 1L, positionRef = "ref1", highlightedText = "a", createdAtEpochMillis = 0L)
        val removed = VaultHighlight(id = 2L, positionRef = "ref2", highlightedText = "b", createdAtEpochMillis = 0L)
        every { sessionManager.isUnlocked("vault-1") } returns true
        coEvery { vaultStore.listEntries() } returns listOf(entry("EPUB").copy(highlights = listOf(kept, removed)))
        every { vaultStore.openReader(fileId) } returns mockk<VaultFileReader>(relaxed = true)
        coEvery { readiumProvider.open(any(), fileIdHex) } returns Result.success(mockk(relaxed = true))
        coEvery { vaultStore.removeHighlight(fileId, 2L) } returns Unit

        val vm = viewModel()
        advanceUntilIdle()
        vm.removeHighlight(2L)
        advanceUntilIdle()

        assertEquals(listOf(kept), vm.highlights.value)
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    @Test
    fun `navigateToBookmark sets pendingNavigationRef, clearPendingNavigation clears it`() = runTest {
        every { sessionManager.isUnlocked("vault-1") } returns true
        coEvery { vaultStore.listEntries() } returns listOf(entry("PDF"))
        every { vaultStore.openReader(fileId) } returns mockk<VaultFileReader>(relaxed = true)

        val vm = viewModel()
        advanceUntilIdle()

        vm.navigateToBookmark("page:5")
        assertEquals("page:5", vm.pendingNavigationRef.value)

        vm.clearPendingNavigation()
        assertNull(vm.pendingNavigationRef.value)
    }
}
