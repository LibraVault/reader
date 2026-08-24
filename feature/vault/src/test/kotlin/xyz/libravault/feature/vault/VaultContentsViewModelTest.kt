package xyz.libravault.feature.vault

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.libravault.core.storage.CoverArtCache
import xyz.libravault.core.storage.ExtractedMetadata
import xyz.libravault.core.storage.MetadataExtractor
import xyz.libravault.core.storage.model.ScannedFile
import xyz.libravault.core.vaultstore.VaultManifestEntry
import xyz.libravault.core.vaultstore.VaultRegistryEntryDto
import xyz.libravault.core.vaultstore.VaultSessionManager
import xyz.libravault.core.vaultstore.VaultStore
import xyz.libravault.core.vaultstore.toHexString
import java.io.ByteArrayInputStream

class VaultContentsViewModelTest {

    private val sessionManager = mockk<VaultSessionManager>()
    private val metadataExtractor = mockk<MetadataExtractor>()
    private val coverArtCache = mockk<CoverArtCache>()
    private val vaultStore = mockk<VaultStore>()
    private val context = mockk<Context>()
    // Relaxed: query()'s relaxed default returns a relaxed Cursor whose
    // moveToFirst() relaxed-defaults to false, so displayNameFor/querySize's
    // `if (idx >= 0 && cursor.moveToFirst())` guard fails and both fall back
    // cleanly (lastPathSegment / 0L) — the same effect a null cursor would
    // have, without fighting MockK over which of ContentResolver's three
    // query() overloads to stub (its 5-arg overload proved unstubbable
    // directly here for reasons not worth chasing further).
    private val contentResolver = mockk<ContentResolver>(relaxed = true)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { context.contentResolver } returns contentResolver
        coEvery { sessionManager.listVaults() } returns listOf(VaultRegistryEntryDto("vault-1", "Personal", 0L))
        every { sessionManager.isUnlocked("vault-1") } returns true
        every { sessionManager.requireUnlocked("vault-1") } returns vaultStore
        coEvery { vaultStore.listEntries() } returns emptyList()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        VaultContentsViewModel(sessionManager, metadataExtractor, coverArtCache, context, SavedStateHandle(mapOf("vaultId" to "vault-1")))

    private fun fakeUri(path: String): Uri {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.lastPathSegment } returns path
        return uri
    }

    @Test
    fun `unsupported file type is marked ERROR without calling MetadataExtractor`() = runTest {
        val uri = fakeUri("mystery.bin")
        every { context.contentResolver.getType(uri) } returns "application/octet-stream"

        val vm = viewModel()
        advanceUntilIdle()
        vm.onFilesPicked(listOf(uri))
        advanceUntilIdle()

        val item = vm.uiState.value.importItems.single()
        assertEquals(ImportItemStatus.ERROR, item.status)
        assertNotNull(item.errorMessage)
        coVerify(exactly = 0) { metadataExtractor.extractWithoutCaching(any()) }
    }

    @Test
    fun `successful import calls extractWithoutCaching, downsamples the cover, then importFile — never touches CoverArtCache save`() = runTest {
        val uri = fakeUri("book.epub")
        every { context.contentResolver.getType(uri) } returns "application/epub+zip"
        val rawCover = byteArrayOf(1, 2, 3)
        val jpegCover = byteArrayOf(4, 5, 6)
        val scannedFileSlot = slot<ScannedFile>()
        coEvery { metadataExtractor.extractWithoutCaching(capture(scannedFileSlot)) } returns
            (ExtractedMetadata(title = "A Book", author = "An Author") to rawCover)
        coEvery { coverArtCache.downsampleToJpeg(rawCover, logKey = "book.epub") } returns jpegCover
        every { context.contentResolver.openInputStream(uri) } returns ByteArrayInputStream(byteArrayOf())
        val entrySlot = slot<VaultManifestEntry>()
        coEvery {
            vaultStore.importFile(any(), any(), "A Book", "An Author", "EPUB", jpegCover)
        } returns mockk()

        val vm = viewModel()
        advanceUntilIdle()
        vm.onFilesPicked(listOf(uri))
        advanceUntilIdle()

        assertEquals("EPUB", scannedFileSlot.captured.format.name)
        val item = vm.uiState.value.importItems.single()
        assertEquals(ImportItemStatus.DONE, item.status)
        coVerify(exactly = 0) { coverArtCache.save(any(), any()) }
        coVerify(exactly = 1) { vaultStore.importFile(any(), any(), "A Book", "An Author", "EPUB", jpegCover) }
    }

    @Test
    fun `a file with no embedded cover imports with a null coverArt, no downsample call`() = runTest {
        val uri = fakeUri("plain.epub")
        every { context.contentResolver.getType(uri) } returns "application/epub+zip"
        coEvery { metadataExtractor.extractWithoutCaching(any()) } returns
            (ExtractedMetadata(title = "Plain", author = "Nobody") to null)
        every { context.contentResolver.openInputStream(uri) } returns ByteArrayInputStream(byteArrayOf())
        coEvery { vaultStore.importFile(any(), any(), "Plain", "Nobody", "EPUB", null) } returns mockk()

        val vm = viewModel()
        advanceUntilIdle()
        vm.onFilesPicked(listOf(uri))
        advanceUntilIdle()

        assertEquals(ImportItemStatus.DONE, vm.uiState.value.importItems.single().status)
        coVerify(exactly = 0) { coverArtCache.downsampleToJpeg(any(), any()) }
    }

    @Test
    fun `one failing file does not stop the rest of the batch`() = runTest {
        val goodUri = fakeUri("good.epub")
        val badUri = fakeUri("bad.epub")
        every { context.contentResolver.getType(goodUri) } returns "application/epub+zip"
        every { context.contentResolver.getType(badUri) } returns "application/epub+zip"
        coEvery { metadataExtractor.extractWithoutCaching(match { it.uri == goodUri }) } returns
            (ExtractedMetadata(title = "Good", author = "A") to null)
        coEvery { metadataExtractor.extractWithoutCaching(match { it.uri == badUri }) } throws RuntimeException("boom")
        every { context.contentResolver.openInputStream(goodUri) } returns ByteArrayInputStream(byteArrayOf())
        coEvery { vaultStore.importFile(any(), any(), "Good", "A", "EPUB", null) } returns mockk()

        val vm = viewModel()
        advanceUntilIdle()
        vm.onFilesPicked(listOf(goodUri, badUri))
        advanceUntilIdle()

        val items = vm.uiState.value.importItems
        assertEquals(ImportItemStatus.DONE, items.first { it.uri == goodUri }.status)
        assertEquals(ImportItemStatus.ERROR, items.first { it.uri == badUri }.status)
    }

    @Test
    fun `dismissImportSummary clears the overlay`() = runTest {
        val uri = fakeUri("book.epub")
        every { context.contentResolver.getType(uri) } returns "application/epub+zip"
        coEvery { metadataExtractor.extractWithoutCaching(any()) } returns
            (ExtractedMetadata(title = "A", author = "B") to null)
        every { context.contentResolver.openInputStream(uri) } returns ByteArrayInputStream(byteArrayOf())
        coEvery { vaultStore.importFile(any(), any(), any(), any(), any(), any()) } returns mockk()

        val vm = viewModel()
        advanceUntilIdle()
        vm.onFilesPicked(listOf(uri))
        advanceUntilIdle()

        vm.dismissImportSummary()

        assertEquals(emptyList<ImportItemUiState>(), vm.uiState.value.importItems)
    }

    @Test
    fun `refresh reports wasLocked when the vault is not unlocked`() = runTest {
        every { sessionManager.isUnlocked("vault-1") } returns false

        val vm = viewModel()
        advanceUntilIdle()

        assertTrueWasLocked(vm)
    }

    // ── Cover art thumbnails (issue #169) ──────────────────────────────────────

    @Test
    fun `refresh decrypts cover art only for entries that have a coverArtFileId`() = runTest {
        val withCover = fakeEntry(fileId = byteArrayOf(1), coverArtFileId = byteArrayOf(9))
        val withoutCover = fakeEntry(fileId = byteArrayOf(2), coverArtFileId = null)
        val coverBytes = byteArrayOf(0x11, 0x22)
        coEvery { vaultStore.listEntries() } returns listOf(withCover, withoutCover)
        coEvery { vaultStore.readCoverArt(byteArrayOf(1)) } returns coverBytes

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.coverArt.size)
        assertEquals(coverBytes, vm.uiState.value.coverArt[byteArrayOf(1).toHexString()])
        coVerify(exactly = 0) { vaultStore.readCoverArt(byteArrayOf(2)) }
    }

    @Test
    fun `refresh drops an entry whose cover decrypt fails, rather than failing the whole list`() = runTest {
        val bad = fakeEntry(fileId = byteArrayOf(3), coverArtFileId = byteArrayOf(9))
        coEvery { vaultStore.listEntries() } returns listOf(bad)
        coEvery { vaultStore.readCoverArt(byteArrayOf(3)) } throws RuntimeException("torn read")

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(emptyMap<String, ByteArray>(), vm.uiState.value.coverArt)
        assertEquals(listOf(bad), vm.uiState.value.entries)
    }

    @Test
    fun `refresh with no covers at all leaves coverArt empty`() = runTest {
        val entry = fakeEntry(fileId = byteArrayOf(4), coverArtFileId = null)
        coEvery { vaultStore.listEntries() } returns listOf(entry)

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(emptyMap<String, ByteArray>(), vm.uiState.value.coverArt)
        coVerify(exactly = 0) { vaultStore.readCoverArt(any()) }
    }

    private fun fakeEntry(fileId: ByteArray, coverArtFileId: ByteArray?) = VaultManifestEntry(
        fileId = fileId,
        title = "Title",
        author = "Author",
        format = "EPUB",
        sizeBytes = 100L,
        addedAtEpochMillis = 0L,
        coverArtFileId = coverArtFileId,
    )

    private fun assertTrueWasLocked(vm: VaultContentsViewModel) {
        org.junit.jupiter.api.Assertions.assertTrue(vm.uiState.value.wasLocked)
    }
}
