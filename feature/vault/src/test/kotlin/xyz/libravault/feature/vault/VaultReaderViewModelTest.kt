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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.readium.r2.shared.publication.Publication
import xyz.libravault.core.vaultcrypto.VaultFileReader
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
}
