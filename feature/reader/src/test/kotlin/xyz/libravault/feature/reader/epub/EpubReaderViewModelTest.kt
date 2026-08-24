package xyz.libravault.feature.reader.epub

import android.net.Uri
import androidx.lifecycle.ViewModelStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
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
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import xyz.libravault.core.logger.LibravaultLogger

class EpubReaderViewModelTest {

    private val readiumProvider = mockk<ReadiumProvider>()
    private val logger = mockk<LibravaultLogger>(relaxed = true)

    // Owns every ViewModel this test class creates so tearDown() can clear() them —
    // same fix as MarkdownReaderViewModelTest's (#554/#553): openPublication()'s
    // viewModelScope.launch can still be mid-flight when Dispatchers.resetMain() runs,
    // and a later exception on that leaked coroutine gets misattributed to whichever
    // test runs next (kotlinx.coroutines.test.UncaughtExceptionsBeforeTest). See #562.
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

    private fun viewModel(): EpubReaderViewModel =
        EpubReaderViewModel(readiumProvider, logger).also {
            viewModelStore.put(it.toString(), it)
        }

    // ── Locator / pending-navigation state ───────────────────────────────────
    //
    // stripHtml itself is already covered by EpubStripHtmlTest — not
    // duplicated here.
    //
    // Constructing a real Locator/Url round-trips through the real
    // android.net.Uri.parse() (Readium's Url.Companion.invoke wraps it), which
    // isn't safely mockable here — a fake Uri produces a Url whose internal
    // behavior (JSON serialization, equality) doesn't match a real one. That
    // needs a real Android runtime, so it's covered by the (not runnable in
    // this sandbox) instrumentation suite rather than a plain JVM unit test.

    @Test
    fun `goToLocatorJson with malformed JSON leaves pendingLocator unset`() {
        val vm = viewModel()

        vm.goToLocatorJson("not valid json{{{")

        assertNull(vm.pendingLocator.value)
    }

    @Test
    fun `clearPendingLocator is a safe no-op when nothing is pending`() {
        val vm = viewModel()

        vm.clearPendingLocator()

        assertNull(vm.pendingLocator.value)
    }

    // ── openPublication ───────────────────────────────────────────────────────

    @Test
    fun `openPublication moves to Ready state on success`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        val publication = mockk<Publication>(relaxed = true)
        every { publication.metadata.title } returns "Test Book"
        coEvery { readiumProvider.open(uri) } returns Result.success(publication)

        val vm = viewModel()
        vm.openPublication(uri)

        val state = vm.state.value
        assertTrue(state is EpubPublicationState.Ready, "expected Ready, got $state")
        assertEquals(uri, (state as EpubPublicationState.Ready).uri)
    }

    @Test
    fun `openPublication moves to Error state on failure`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        coEvery { readiumProvider.open(uri) } returns Result.failure(RuntimeException("corrupt epub"))

        val vm = viewModel()
        vm.openPublication(uri)

        val state = vm.state.value
        assertTrue(state is EpubPublicationState.Error, "expected Error, got $state")
        assertEquals("corrupt epub", (state as EpubPublicationState.Error).message)
    }

    @Test
    fun `openPublication moves to DrmProtected state when the publication is DRM-restricted`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        coEvery { readiumProvider.open(uri) } returns Result.failure(DrmProtectedException("Adobe ADEPT"))

        val vm = viewModel()
        vm.openPublication(uri)

        val state = vm.state.value
        assertTrue(state is EpubPublicationState.DrmProtected, "expected DrmProtected, got $state")
        assertEquals("Adobe ADEPT", (state as EpubPublicationState.DrmProtected).schemeName)
    }

    @Test
    fun `openPublication is a no-op when already Ready for the same uri`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        val publication = mockk<Publication>(relaxed = true)
        every { publication.metadata.title } returns "Test Book"
        coEvery { readiumProvider.open(uri) } returns Result.success(publication)

        val vm = viewModel()
        vm.openPublication(uri)
        vm.openPublication(uri)

        coVerify(exactly = 1) { readiumProvider.open(uri) }
    }

    // ── Chapter index/count + previous-chapter nav (#138) ──────────────────────
    //
    // These stub Publication.get(link) to return null so fetchAndClean() short-
    // circuits to null immediately — that decouples "did the TTS cursor move
    // correctly" from chapter text extraction (already untested above the
    // stripHtml layer; see the class-level comment on why a real Link/Locator/Url
    // isn't safely mockable here), while still exercising the real cursor
    // arithmetic in getNextChapterText/getPreviousChapterText.

    @Test
    fun `ttsChapterIndex and ttsChapterCount are 0 before any publication is open`() {
        val vm = viewModel()

        assertEquals(0, vm.ttsChapterIndex)
        assertEquals(0, vm.ttsChapterCount)
    }

    @Test
    fun `ttsChapterCount reflects the reading order once the publication is ready`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        val publication = mockk<Publication>(relaxed = true)
        every { publication.metadata.title } returns "Test Book"
        every { publication.readingOrder } returns listOf(
            mockk<Link>(relaxed = true), mockk<Link>(relaxed = true), mockk<Link>(relaxed = true),
        )
        coEvery { readiumProvider.open(uri) } returns Result.success(publication)

        val vm = viewModel()
        vm.openPublication(uri)

        assertEquals(3, vm.ttsChapterCount)
        assertEquals(0, vm.ttsChapterIndex)
    }

    @Test
    fun `getNextChapterText then getPreviousChapterText returns the cursor to where it started`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        val publication = mockk<Publication>(relaxed = true)
        every { publication.metadata.title } returns "Test Book"
        every { publication.readingOrder } returns listOf(
            mockk<Link>(relaxed = true), mockk<Link>(relaxed = true),
        )
        every { publication.get(any<Link>()) } returns null
        coEvery { readiumProvider.open(uri) } returns Result.success(publication)

        val vm = viewModel()
        vm.openPublication(uri)

        vm.getNextChapterText() // ttsSpineIndex: -1 -> 0
        assertEquals(0, vm.ttsChapterIndex)

        vm.getNextChapterText() // 0 -> 1
        assertEquals(1, vm.ttsChapterIndex)

        vm.getPreviousChapterText() // 1 -> 0
        assertEquals(0, vm.ttsChapterIndex)
    }

    @Test
    fun `getPreviousChapterText returns null and leaves the cursor unmoved at the start of the book`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        val publication = mockk<Publication>(relaxed = true)
        every { publication.metadata.title } returns "Test Book"
        every { publication.readingOrder } returns listOf(
            mockk<Link>(relaxed = true), mockk<Link>(relaxed = true),
        )
        every { publication.get(any<Link>()) } returns null
        coEvery { readiumProvider.open(uri) } returns Result.success(publication)

        val vm = viewModel()
        vm.openPublication(uri)
        vm.getNextChapterText() // anchors at chapter 0

        val previous = vm.getPreviousChapterText()

        assertNull(previous)
        assertEquals(0, vm.ttsChapterIndex)
    }

    @Test
    fun `getPreviousChapterText returns null when no publication is open`() = runTest {
        val vm = viewModel()

        assertNull(vm.getPreviousChapterText())
    }
}
