package xyz.libravault.feature.reader.markdown

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    // Owns every ViewModel this test class creates so tearDown() can clear() them.
    // Without this, a ViewModel's viewModelScope outlives the test that created it —
    // its coroutine can still be mid-flight (e.g. the Dispatchers.IO hop in load())
    // when Dispatchers.resetMain() runs, and a later exception on that leaked
    // coroutine gets misattributed to whichever test runs next
    // (kotlinx.coroutines.test.UncaughtExceptionsBeforeTest). See #553.
    private val viewModelStore = ViewModelStore()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        viewModelStore.clear()
        Dispatchers.resetMain()
    }

    private fun viewModel(): MarkdownReaderViewModel =
        MarkdownReaderViewModel(context, assetResolver, logger).also {
            viewModelStore.put(it.toString(), it)
        }

    // ── tearDown cancels leaked ViewModel coroutines (#553) ─────────────────────

    @Test
    fun `tearDown cancels a leaked ViewModel coroutine`() = runTest {
        val vm = viewModel()
        val leaked = vm.viewModelScope.launch { awaitCancellation() }

        tearDown()

        assertTrue(leaked.isCancelled)
    }

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

    // ── Read Aloud (#276) chapter walk ─────────────────────────────────────────

    private val threeChapterMarkdown = """
        # Chapter One
        First chapter text.

        # Chapter Two
        Second chapter text.

        # Chapter Three
        Third chapter text.
    """.trimIndent()

    @Test
    fun `getChapterTextFromProgression returns null when no document is loaded`() = runTest {
        val vm = viewModel()

        assertNull(vm.getChapterTextFromProgression(null))
    }

    @Test
    fun `getChapterTextFromProgression with null fraction starts at the first chapter`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        every { resolver.openInputStream(uri) } returns threeChapterMarkdown.byteInputStream()
        val vm = viewModel()
        vm.load(uri).join()

        val text = vm.getChapterTextFromProgression(null)

        assertTrue(text!!.contains("First chapter text"), "expected chapter one text, got: $text")
    }

    @Test
    fun `getChapterTextFromProgression anchors to the chapter nearest the given fraction`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        every { resolver.openInputStream(uri) } returns threeChapterMarkdown.byteInputStream()
        val vm = viewModel()
        vm.load(uri).join()

        // 3 chapters; sectionIndexForFraction(0.7, 3) rounds to index 2 (the third).
        val text = vm.getChapterTextFromProgression(0.7)

        assertTrue(text!!.contains("Third chapter text"), "expected chapter three text, got: $text")
    }

    @Test
    fun `getNextChapterText walks forward from the chapter getChapterTextFromProgression anchored`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        every { resolver.openInputStream(uri) } returns threeChapterMarkdown.byteInputStream()
        val vm = viewModel()
        vm.load(uri).join()
        vm.getChapterTextFromProgression(null) // anchors at chapter 0 (Chapter One)

        val next = vm.getNextChapterText()

        assertTrue(next!!.contains("Second chapter text"), "expected chapter two text, got: $next")
    }

    @Test
    fun `getNextChapterText returns null at the end of the document`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        every { resolver.openInputStream(uri) } returns threeChapterMarkdown.byteInputStream()
        val vm = viewModel()
        vm.load(uri).join()
        vm.getChapterTextFromProgression(0.99) // anchors at the last chapter (Chapter Three)

        assertNull(vm.getNextChapterText())
    }

    // ── Chapter index/count + previous-chapter nav (#138) ──────────────────────

    @Test
    fun `ttsChapterIndex and ttsChapterCount are 0 before any Read Aloud session`() {
        val vm = viewModel()

        assertEquals(0, vm.ttsChapterIndex)
        assertEquals(0, vm.ttsChapterCount)
    }

    @Test
    fun `ttsChapterCount reflects the narratable chapter count once anchored`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        every { resolver.openInputStream(uri) } returns threeChapterMarkdown.byteInputStream()
        val vm = viewModel()
        vm.load(uri).join()

        vm.getChapterTextFromProgression(null)

        assertEquals(3, vm.ttsChapterCount)
        assertEquals(0, vm.ttsChapterIndex)
    }

    @Test
    fun `getPreviousChapterText walks back and updates ttsChapterIndex`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        every { resolver.openInputStream(uri) } returns threeChapterMarkdown.byteInputStream()
        val vm = viewModel()
        vm.load(uri).join()
        vm.getChapterTextFromProgression(0.99) // anchors at chapter 2 (Chapter Three)
        assertEquals(2, vm.ttsChapterIndex)

        val previous = vm.getPreviousChapterText()

        assertTrue(previous!!.contains("Second chapter text"), "expected chapter two text, got: $previous")
        assertEquals(1, vm.ttsChapterIndex)
    }

    @Test
    fun `getPreviousChapterText returns null and leaves the cursor unmoved at the start`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        every { resolver.openInputStream(uri) } returns threeChapterMarkdown.byteInputStream()
        val vm = viewModel()
        vm.load(uri).join()
        vm.getChapterTextFromProgression(null) // anchors at chapter 0

        val previous = vm.getPreviousChapterText()

        assertNull(previous)
        assertEquals(0, vm.ttsChapterIndex)
    }
}
