package xyz.libravault.feature.reader.epub

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import xyz.libravault.core.domain.model.ContentSource
import xyz.libravault.core.logger.LibravaultLogger
import xyz.libravault.core.vaultstore.VaultSessionManager

class EpubReaderViewModelTest {

    private val readiumProvider = mockk<ReadiumProvider>()
    private val sessionManager = mockk<VaultSessionManager>()
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
        EpubReaderViewModel(readiumProvider, sessionManager, logger).also {
            viewModelStore.put(it.toString(), it)
        }

    // ── tearDown cancels leaked ViewModel coroutines (#562) ─────────────────────

    @Test
    fun `tearDown cancels a leaked ViewModel coroutine`() = runTest {
        val vm = viewModel()
        val leaked = vm.viewModelScope.launch { awaitCancellation() }

        tearDown()

        assertTrue(leaked.isCancelled)
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

    // ── openPublication (ContentSource.RealFile — #505) ─────────────────────
    //
    // ContentSource.RealFile.uriString is a plain String, so these tests
    // never need to construct a real android.net.Uri — ReadiumProvider.open
    // itself takes a String for the same reason (see its doc comment).
    // ContentSource.VaultEntry resolution is covered separately below.

    @Test
    fun `openPublication moves to Ready state on success`() = runTest {
        val source = ContentSource.RealFile("content://test/book.epub")
        val publication = mockk<Publication>(relaxed = true)
        every { publication.metadata.title } returns "Test Book"
        coEvery { readiumProvider.open(source.uriString) } returns Result.success(publication)

        val vm = viewModel()
        vm.openPublication(source)

        val state = vm.state.value
        assertTrue(state is EpubPublicationState.Ready, "expected Ready, got $state")
        assertEquals(source, (state as EpubPublicationState.Ready).source)
    }

    @Test
    fun `openPublication moves to Error state on failure`() = runTest {
        val source = ContentSource.RealFile("content://test/book.epub")
        coEvery { readiumProvider.open(source.uriString) } returns Result.failure(RuntimeException("corrupt epub"))

        val vm = viewModel()
        vm.openPublication(source)

        val state = vm.state.value
        assertTrue(state is EpubPublicationState.Error, "expected Error, got $state")
        assertEquals("corrupt epub", (state as EpubPublicationState.Error).message)
    }

    @Test
    fun `openPublication moves to DrmProtected state when the publication is DRM-restricted`() = runTest {
        val source = ContentSource.RealFile("content://test/book.epub")
        coEvery { readiumProvider.open(source.uriString) } returns Result.failure(DrmProtectedException("Adobe ADEPT"))

        val vm = viewModel()
        vm.openPublication(source)

        val state = vm.state.value
        assertTrue(state is EpubPublicationState.DrmProtected, "expected DrmProtected, got $state")
        assertEquals("Adobe ADEPT", (state as EpubPublicationState.DrmProtected).schemeName)
    }

    @Test
    fun `openPublication is a no-op when already Ready for the same source`() = runTest {
        val source = ContentSource.RealFile("content://test/book.epub")
        val publication = mockk<Publication>(relaxed = true)
        every { publication.metadata.title } returns "Test Book"
        coEvery { readiumProvider.open(source.uriString) } returns Result.success(publication)

        val vm = viewModel()
        vm.openPublication(source)
        vm.openPublication(source)

        coVerify(exactly = 1) { readiumProvider.open(source.uriString) }
    }

    // ── openPublication (ContentSource.VaultEntry — #505) ───────────────────

    @Test
    fun `openPublication resolves a vault reader then opens it via openVaultFile`() = runTest {
        val source = ContentSource.VaultEntry("vault-1", "aabbcc", xyz.libravault.core.domain.model.MediaFormat.EPUB)
        val store = mockk<xyz.libravault.core.vaultstore.VaultStore>()
        val reader = mockk<xyz.libravault.core.vaultcrypto.VaultFileReader>()
        val publication = mockk<Publication>(relaxed = true)
        every { publication.metadata.title } returns "Vault Book"
        every { sessionManager.requireUnlocked("vault-1") } returns store
        every { store.openReader(any()) } returns reader
        coEvery { readiumProvider.openVaultFile(reader, "aabbcc") } returns Result.success(publication)

        val vm = viewModel()
        vm.openPublication(source)

        val state = vm.state.value
        assertTrue(state is EpubPublicationState.Ready, "expected Ready, got $state")
        assertEquals(source, (state as EpubPublicationState.Ready).source)
    }

    @Test
    fun `openPublication surfaces a locked vault as an Error state, not a crash`() = runTest {
        val source = ContentSource.VaultEntry("vault-1", "aabbcc", xyz.libravault.core.domain.model.MediaFormat.EPUB)
        every { sessionManager.requireUnlocked("vault-1") } throws IllegalStateException("Vault vault-1 is not unlocked")

        val vm = viewModel()
        vm.openPublication(source)

        val state = vm.state.value
        assertTrue(state is EpubPublicationState.Error, "expected Error, got $state")
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
        val source = ContentSource.RealFile("content://test/book.epub")
        val publication = mockk<Publication>(relaxed = true)
        every { publication.metadata.title } returns "Test Book"
        every { publication.readingOrder } returns listOf(
            mockk<Link>(relaxed = true), mockk<Link>(relaxed = true), mockk<Link>(relaxed = true),
        )
        coEvery { readiumProvider.open(source.uriString) } returns Result.success(publication)

        val vm = viewModel()
        vm.openPublication(source)

        assertEquals(3, vm.ttsChapterCount)
        assertEquals(0, vm.ttsChapterIndex)
    }

    @Test
    fun `getNextChapterText then getPreviousChapterText returns the cursor to where it started`() = runTest {
        val source = ContentSource.RealFile("content://test/book.epub")
        val publication = mockk<Publication>(relaxed = true)
        every { publication.metadata.title } returns "Test Book"
        every { publication.readingOrder } returns listOf(
            mockk<Link>(relaxed = true), mockk<Link>(relaxed = true),
        )
        every { publication.get(any<Link>()) } returns null
        coEvery { readiumProvider.open(source.uriString) } returns Result.success(publication)

        val vm = viewModel()
        vm.openPublication(source)

        vm.getNextChapterText() // ttsSpineIndex: -1 -> 0
        assertEquals(0, vm.ttsChapterIndex)

        vm.getNextChapterText() // 0 -> 1
        assertEquals(1, vm.ttsChapterIndex)

        vm.getPreviousChapterText() // 1 -> 0
        assertEquals(0, vm.ttsChapterIndex)
    }

    @Test
    fun `getPreviousChapterText returns null and leaves the cursor unmoved at the start of the book`() = runTest {
        val source = ContentSource.RealFile("content://test/book.epub")
        val publication = mockk<Publication>(relaxed = true)
        every { publication.metadata.title } returns "Test Book"
        every { publication.readingOrder } returns listOf(
            mockk<Link>(relaxed = true), mockk<Link>(relaxed = true),
        )
        every { publication.get(any<Link>()) } returns null
        coEvery { readiumProvider.open(source.uriString) } returns Result.success(publication)

        val vm = viewModel()
        vm.openPublication(source)
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

    // ── collapseTocToChapterSpec (#596) ─────────────────────────────────────────
    //
    // Pure-data logic extracted from EpubReaderViewModel.buildChapters specifically so
    // it's testable without a real Link/Url — see the class-level comment above on why
    // those aren't safely constructible in a plain JVM unit test here.

    @Test
    fun `collapseTocToChapterSpec keeps one chapter per TOC entry when each targets a distinct spine item`() {
        val tocMatches = listOf(0 to "Preface", 1 to "Chapter One", 2 to "Chapter Two")
        val spineTitles = listOf<String?>(null, null, null)

        val result = collapseTocToChapterSpec(tocMatches, spineTitles)

        assertEquals(listOf(0 to "Preface", 1 to "Chapter One", 2 to "Chapter Two"), result)
    }

    @Test
    fun `collapseTocToChapterSpec collapses TOC entries landing on the same spine item, keeping the first title`() {
        // A single "chapter1.xhtml" spine file with two nav-doc sub-headings pointing
        // into it (different anchors, both stripped to the same spine index upstream).
        val tocMatches = listOf(0 to "Chapter One", 0 to "Chapter One — Section 2", 1 to "Chapter Two")
        val spineTitles = listOf<String?>(null, null)

        val result = collapseTocToChapterSpec(tocMatches, spineTitles)

        assertEquals(listOf(0 to "Chapter One", 1 to "Chapter Two"), result)
    }

    @Test
    fun `collapseTocToChapterSpec falls back to one chapter per spine item when no TOC entry resolves`() {
        val spineTitles = listOf<String?>("Cover", null, "Appendix")

        val result = collapseTocToChapterSpec(emptyList(), spineTitles)

        assertEquals(
            listOf(0 to "Cover", 1 to "Chapter 2", 2 to "Appendix"),
            result,
        )
    }

    @Test
    fun `collapseTocToChapterSpec falls back to the spine link's own title when the TOC entry has none`() {
        val tocMatches = listOf<Pair<Int, String?>>(0 to null)
        val spineTitles = listOf<String?>("Spine Title")

        val result = collapseTocToChapterSpec(tocMatches, spineTitles)

        assertEquals(listOf(0 to "Spine Title"), result)
    }

    @Test
    fun `collapseTocToChapterSpec ignores a TOC match pointing outside the spine's range`() {
        val tocMatches = listOf(5 to "Ghost chapter", 0 to "Real chapter")
        val spineTitles = listOf<String?>(null)

        val result = collapseTocToChapterSpec(tocMatches, spineTitles)

        assertEquals(listOf(0 to "Real chapter"), result)
    }

    @Test
    fun `collapseTocToChapterSpec returns chapters in ascending spine order even when the TOC lists them out of order`() {
        // A malformed/reordered nav doc — e.g. an appendix linked before the main
        // chapters — must not make Read Aloud's "next chapter" walk out of the book's
        // actual physical reading order, and chapterIndexForSpineIndex's "nearest
        // preceding chapter" search depends on this ordering too.
        val tocMatches = listOf(2 to "Chapter Two", 0 to "Chapter One", 1 to "Appendix (linked early)")
        val spineTitles = listOf<String?>(null, null, null)

        val result = collapseTocToChapterSpec(tocMatches, spineTitles)

        assertEquals(
            listOf(0 to "Chapter One", 1 to "Appendix (linked early)", 2 to "Chapter Two"),
            result,
        )
    }

    // ── chapterIndexForSpineIndex (#596) ────────────────────────────────────────

    private fun chapterAt(spineIndex: Int) =
        EpubReaderViewModel.EpubChapter(
            chapter = xyz.libravault.core.domain.model.ReaderChapter(title = "t", index = spineIndex) { "" },
            spineIndex = spineIndex,
        )

    @Test
    fun `chapterIndexForSpineIndex returns 0 when there are no chapters`() {
        assertEquals(0, chapterIndexForSpineIndex(emptyList(), spineIndex = 3))
    }

    @Test
    fun `chapterIndexForSpineIndex finds the chapter starting exactly at the given spine index`() {
        val chapters = listOf(chapterAt(0), chapterAt(2), chapterAt(5))

        assertEquals(1, chapterIndexForSpineIndex(chapters, spineIndex = 2))
    }

    @Test
    fun `chapterIndexForSpineIndex attributes a spine item with no chapter of its own to the nearest preceding chapter`() {
        // e.g. a cover or copyright page (spine index 1) between chapter 0 and chapter 2,
        // neither of which has its own TOC entry.
        val chapters = listOf(chapterAt(0), chapterAt(2), chapterAt(5))

        assertEquals(0, chapterIndexForSpineIndex(chapters, spineIndex = 1))
    }

    @Test
    fun `chapterIndexForSpineIndex attributes a spine item before the first chapter to the first chapter`() {
        val chapters = listOf(chapterAt(2), chapterAt(5))

        assertEquals(0, chapterIndexForSpineIndex(chapters, spineIndex = 0))
    }

    // ── buildTocEntries (#596) ───────────────────────────────────────────────────
    //
    // Same "extract the pure part" approach as collapseTocToChapterSpec — the 3-arg
    // overload here takes a plain (Link) -> String? lambda instead of calling
    // Publication.locatorFromLink directly, so the recursion/level/fallback-title
    // logic is testable without a real Locator/Url.

    @Test
    fun `buildTocEntries skips a link its locator lambda can't resolve`() {
        val link = mockk<Link>(relaxed = true)
        every { link.title } returns "Untitled Chapter"

        val result = buildTocEntries(listOf(link), level = 0) { null }

        assertTrue(result.isEmpty())
    }

    @Test
    fun `buildTocEntries walks children depth-first, immediately after their parent`() {
        val child = mockk<Link>(relaxed = true)
        every { child.title } returns "Section 1.1"
        every { child.children } returns emptyList()
        val parent = mockk<Link>(relaxed = true)
        every { parent.title } returns "Chapter 1"
        every { parent.children } returns listOf(child)
        val sibling = mockk<Link>(relaxed = true)
        every { sibling.title } returns "Chapter 2"
        every { sibling.children } returns emptyList()

        val result = buildTocEntries(listOf(parent, sibling), level = 0) { "loc:${it.title}" }

        assertEquals(
            listOf("Chapter 1" to 0, "Section 1.1" to 1, "Chapter 2" to 0),
            result.map { it.title to it.level },
        )
    }

    @Test
    fun `buildTocEntries falls back to Untitled for a link with a blank title`() {
        val link = mockk<Link>(relaxed = true)
        every { link.title } returns "   "
        every { link.children } returns emptyList()

        val result = buildTocEntries(listOf(link), level = 0) { "loc" }

        assertEquals("Untitled", result.single().title)
    }
}
