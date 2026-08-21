package xyz.libravault.feature.reader.epub

import android.net.Uri
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
import org.readium.r2.shared.publication.Publication
import xyz.libravault.core.logger.LibravaultLogger

class EpubReaderViewModelTest {

    private val readiumProvider = mockk<ReadiumProvider>()
    private val logger = mockk<LibravaultLogger>(relaxed = true)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = EpubReaderViewModel(readiumProvider, logger)

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
}
