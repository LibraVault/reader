package xyz.libravault.feature.reader.markdown

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.libravault.core.logger.LibravaultLogger
import xyz.libravault.core.storage.MarkdownAssetResolver

class MarkdownReaderViewModelTest {

    private val resolver = mockk<ContentResolver>()
    private val context = mockk<Context>(relaxed = true) {
        every { contentResolver } returns resolver
    }
    private val assetResolver = mockk<MarkdownAssetResolver>(relaxed = true)
    private val logger = mockk<LibravaultLogger>(relaxed = true)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = MarkdownReaderViewModel(context, assetResolver, logger)

    @Test
    fun `load reads file content into Ready state`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        every { resolver.openInputStream(uri) } returns "# Hello".byteInputStream()

        val vm = viewModel()
        vm.load(uri).join()

        val state = vm.state.value
        assertTrue(state is MarkdownPublicationState.Ready, "expected Ready, got $state")
        assertEquals("# Hello", (state as MarkdownPublicationState.Ready).text)
        assertEquals(uri, state.uri)
    }

    @Test
    fun `load with unopenable stream produces Error state`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        every { resolver.openInputStream(uri) } returns null

        val vm = viewModel()
        vm.load(uri).join()

        assertTrue(vm.state.value is MarkdownPublicationState.Error)
    }

    @Test
    fun `load is idempotent for the same already-ready uri`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        // If the idempotency guard were missing, load()'s second call would consult
        // this mock again — mockk defaults to returning the same stubbed stream, but
        // since a ByteArrayInputStream can only be read once, a real regression here
        // (reading the exhausted stream a second time) would flip the state to Error.
        every { resolver.openInputStream(uri) } returns "content".byteInputStream()

        val vm = viewModel()
        vm.load(uri).join()
        vm.load(uri)

        val state = vm.state.value
        assertTrue(state is MarkdownPublicationState.Ready)
        assertEquals("content", (state as MarkdownPublicationState.Ready).text)
    }

    @Test
    fun `file exceeding the size cap produces Error state`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        val oversized = ByteArray(MarkdownReaderViewModel.MAX_FILE_BYTES + 1)
        every { resolver.openInputStream(uri) } returns oversized.inputStream()

        val vm = viewModel()
        vm.load(uri).join()

        assertTrue(vm.state.value is MarkdownPublicationState.Error)
    }

    @Test
    fun `file at exactly the size cap is accepted`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        val exact = ByteArray(MarkdownReaderViewModel.MAX_FILE_BYTES) { 'a'.code.toByte() }
        every { resolver.openInputStream(uri) } returns exact.inputStream()

        val vm = viewModel()
        vm.load(uri).join()

        assertTrue(vm.state.value is MarkdownPublicationState.Ready)
    }

    // ── vaultTreeUri / asset parent directory resolution ──────────────────────

    @Test
    fun `load with a vaultTreeUri resolves and stores the asset parent directory`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        val vaultTreeUri = mockk<Uri>(relaxed = true)
        val parentDirectory = mockk<DocumentFile>(relaxed = true)
        every { resolver.openInputStream(uri) } returns "content".byteInputStream()
        every { assetResolver.findParentDirectory(vaultTreeUri, uri) } returns parentDirectory

        val vm = viewModel()
        vm.load(uri, vaultTreeUri).join()

        val state = vm.state.value
        assertTrue(state is MarkdownPublicationState.Ready)
        assertEquals(parentDirectory, (state as MarkdownPublicationState.Ready).assetParentDirectory)
    }

    @Test
    fun `load without a vaultTreeUri never consults the asset resolver`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        every { resolver.openInputStream(uri) } returns "content".byteInputStream()

        val vm = viewModel()
        vm.load(uri, vaultTreeUri = null).join()

        val state = vm.state.value
        assertTrue(state is MarkdownPublicationState.Ready)
        assertEquals(null, (state as MarkdownPublicationState.Ready).assetParentDirectory)
        verify(exactly = 0) { assetResolver.findParentDirectory(any(), any()) }
    }
}
