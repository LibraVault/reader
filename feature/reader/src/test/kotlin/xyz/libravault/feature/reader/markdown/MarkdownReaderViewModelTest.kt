package xyz.libravault.feature.reader.markdown

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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
import xyz.libravault.core.domain.model.ContentSource
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.core.logger.LibravaultLogger
import xyz.libravault.core.storage.MarkdownAssetResolver
import xyz.libravault.core.vaultcrypto.ChunkedVaultWriter
import xyz.libravault.core.vaultcrypto.VaultFileReader
import xyz.libravault.core.vaultcrypto.VaultFormat
import xyz.libravault.core.vaultstore.VaultSessionManager
import xyz.libravault.core.vaultstore.VaultStore
import java.security.SecureRandom

class MarkdownReaderViewModelTest {

    private val resolver = mockk<ContentResolver>()
    private val context = mockk<Context>(relaxed = true) {
        every { contentResolver } returns resolver
    }
    private val assetResolver = mockk<MarkdownAssetResolver>(relaxed = true)
    private val sessionManager = mockk<VaultSessionManager>()
    private val logger = mockk<LibravaultLogger>(relaxed = true)
    private val realFileSource = ContentSource.RealFile("content://test/doc.md")

    // ContentSource.RealFile.uriString flows through MarkdownReaderViewModel's real
    // Uri.parse() call (ContentResolver.openInputStream needs an actual Uri — unlike
    // ReadiumProvider's String-based entry point, there's no way around constructing
    // one here). Same mockkStatic(Uri::class) pattern LibraryViewModelTest/
    // SettingsViewModelTest already use for the same reason. Every call returns the
    // same relaxed mock instance, which is what resolver.openInputStream(any()) below
    // matches against.
    private lateinit var parsedUri: Uri

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
        mockkStatic(Uri::class)
        parsedUri = mockk(relaxed = true)
        every { Uri.parse(any()) } returns parsedUri
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Uri::class)
        viewModelStore.clear()
        Dispatchers.resetMain()
    }

    private fun viewModel(): MarkdownReaderViewModel =
        MarkdownReaderViewModel(context, assetResolver, sessionManager, logger).also {
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
        every { resolver.openInputStream(parsedUri) } returns "# Hello".byteInputStream()

        val vm = viewModel()
        vm.load(realFileSource).join()

        val state = vm.state.value
        assertTrue(state is MarkdownPublicationState.Ready, "expected Ready, got $state")
        assertEquals("# Hello", (state as MarkdownPublicationState.Ready).text)
        assertEquals(realFileSource, state.source)
    }

    @Test
    fun `load with unopenable stream produces Error state`() = runTest {
        every { resolver.openInputStream(parsedUri) } returns null

        val vm = viewModel()
        vm.load(realFileSource).join()

        assertTrue(vm.state.value is MarkdownPublicationState.Error)
    }

    @Test
    fun `load is idempotent for the same already-ready source`() = runTest {
        // If the idempotency guard were missing, load()'s second call would consult
        // this mock again — mockk defaults to returning the same stubbed stream, but
        // since a ByteArrayInputStream can only be read once, a real regression here
        // (reading the exhausted stream a second time) would flip the state to Error.
        every { resolver.openInputStream(parsedUri) } returns "content".byteInputStream()

        val vm = viewModel()
        vm.load(realFileSource).join()
        vm.load(realFileSource)

        val state = vm.state.value
        assertTrue(state is MarkdownPublicationState.Ready)
        assertEquals("content", (state as MarkdownPublicationState.Ready).text)
    }

    @Test
    fun `file exceeding the size cap produces Error state`() = runTest {
        val oversized = ByteArray(MarkdownReaderViewModel.MAX_FILE_BYTES + 1)
        every { resolver.openInputStream(parsedUri) } returns oversized.inputStream()

        val vm = viewModel()
        vm.load(realFileSource).join()

        assertTrue(vm.state.value is MarkdownPublicationState.Error)
    }

    @Test
    fun `file at exactly the size cap is accepted`() = runTest {
        val exact = ByteArray(MarkdownReaderViewModel.MAX_FILE_BYTES) { 'a'.code.toByte() }
        every { resolver.openInputStream(parsedUri) } returns exact.inputStream()

        val vm = viewModel()
        vm.load(realFileSource).join()

        assertTrue(vm.state.value is MarkdownPublicationState.Ready)
    }

    // ── ContentSource.VaultEntry (#505) ──────────────────────────────────────

    private val vaultSource = ContentSource.VaultEntry("vault-1", "aabbcc", MediaFormat.MARKDOWN)

    private fun stubVaultReader(text: String): VaultFileReader {
        val store = mockk<VaultStore>()
        val reader = mockk<VaultFileReader>()
        val bytes = text.toByteArray(Charsets.UTF_8)
        every { sessionManager.requireUnlocked("vault-1") } returns store
        every { store.openReader(any()) } returns reader
        every { reader.plainSize } returns bytes.size.toLong()
        every { reader.readAt(0L, bytes.size) } returns bytes
        every { reader.close() } returns Unit
        return reader
    }

    /**
     * Real crypto, not a mocked [VaultFileReader] — writes a genuinely
     * AES-256-GCM-encrypted vault file via [ChunkedVaultWriter] (core:vaultcrypto,
     * the same primitive `VaultStore.importFile` uses) to a JVM temp file, then
     * lets [MarkdownReaderViewModel] decrypt it for real through the unmocked
     * [VaultFileReader]/[VaultStore.openReader] chain. Security-audit checklist
     * item (#505 issue, PRD §5's "same rigor as PR #166's CoverArtCache audit"):
     * proves the vault Markdown call site (a) decrypts correctly end-to-end and
     * (b) the plaintext marker text exists ONLY in [MarkdownPublicationState.Ready.text]
     * (in-memory) — never written to [tempFile]'s directory or anywhere else on
     * disk, since `readVaultText`'s vault branch never touches a File/ContentResolver
     * API at all, only `VaultFileReader.readAt`. `VaultProxyFdHost`/
     * `VaultMemfdFallback` (the PDF call site's equivalent) already has its own
     * byte-correctness coverage in `core:vaultcontent`'s `VaultProxyFdCallbackTest`;
     * the real FUSE/`StorageManager`/`memfd_create` plumbing underneath both is
     * established in this codebase as physical-device-only to verify (see that
     * test's own doc comment) — not re-attempted here with a fake.
     */
    private fun realVaultReader(plaintext: ByteArray): Pair<java.io.File, VaultFileReader> {
        val tempFile = java.io.File.createTempFile("vault-markdown-leak-test", ".bin")
        tempFile.deleteOnExit()
        val vmk = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val fileId = ByteArray(VaultFormat.FILE_ID_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
        tempFile.outputStream().use { out ->
            ChunkedVaultWriter.encrypt(
                vmk = vmk,
                fileId = fileId,
                totalPlaintextLength = plaintext.size.toLong(),
                input = plaintext.inputStream(),
                output = out,
            )
        }
        // The encrypted file itself must not contain the plaintext marker either —
        // otherwise this test would trivially pass regardless of whether decryption
        // even ran, since the "no leak" assertion below only scans the *directory*.
        assertTrue(!tempFile.readBytes().let { encrypted -> containsSubarray(encrypted, plaintext) })
        return tempFile to VaultFileReader(tempFile, vmk, fileId)
    }

    private fun containsSubarray(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    @Test
    fun `vault markdown decrypts real AES-256-GCM ciphertext end-to-end with the plaintext marker only ever in memory`() = runTest {
        val marker = "MARKER-4f3a91-vault-markdown-plaintext-should-only-live-in-memory"
        val plaintext = "# Real Vault Doc\n\n$marker\n".toByteArray(Charsets.UTF_8)
        val (tempFile, reader) = realVaultReader(plaintext)
        val store = mockk<VaultStore>()
        every { sessionManager.requireUnlocked("vault-1") } returns store
        every { store.openReader(any()) } returns reader

        val vm = viewModel()
        vm.load(vaultSource).join()

        val state = vm.state.value
        assertTrue(state is MarkdownPublicationState.Ready, "expected Ready, got $state")
        assertTrue((state as MarkdownPublicationState.Ready).text.contains(marker), "decrypted text must contain the marker")
        // The only file this test ever wrote is the ciphertext itself (already
        // asserted marker-free above) — the decrypt path (readAt -> String, held
        // only in ReaderUiState/MarkdownPublicationState) never creates a second
        // file, so scanning the temp directory for the plaintext marker covers
        // every file this test's vault decrypt could plausibly have leaked into.
        val siblingFiles = tempFile.parentFile?.listFiles { f -> f != tempFile && f.name.contains("vault-markdown-leak-test") }
            ?: emptyArray()
        assertTrue(siblingFiles.isEmpty(), "vault markdown decrypt must not create any additional file on disk")
    }

    @Test
    fun `load reads a vault entry into Ready state`() = runTest {
        stubVaultReader("# Vault Doc")

        val vm = viewModel()
        vm.load(vaultSource).join()

        val state = vm.state.value
        assertTrue(state is MarkdownPublicationState.Ready, "expected Ready, got $state")
        assertEquals("# Vault Doc", (state as MarkdownPublicationState.Ready).text)
        assertEquals(vaultSource, state.source)
    }

    @Test
    fun `load never resolves an asset parent directory for vault entries`() = runTest {
        stubVaultReader("content")

        val vm = viewModel()
        vm.load(vaultSource).join()

        val state = vm.state.value
        assertTrue(state is MarkdownPublicationState.Ready)
        assertEquals(null, (state as MarkdownPublicationState.Ready).assetParentDirectory)
        verify(exactly = 0) { assetResolver.findParentDirectory(any(), any()) }
    }

    @Test
    fun `load surfaces a locked vault as an Error state, not a crash`() = runTest {
        every { sessionManager.requireUnlocked("vault-1") } throws IllegalStateException("Vault vault-1 is not unlocked")

        val vm = viewModel()
        vm.load(vaultSource).join()

        assertTrue(vm.state.value is MarkdownPublicationState.Error)
    }

    @Test
    fun `a vault entry exceeding the size cap produces Error state, closing the gap the old vault-only path had`() = runTest {
        val store = mockk<VaultStore>()
        val reader = mockk<VaultFileReader>()
        every { sessionManager.requireUnlocked("vault-1") } returns store
        every { store.openReader(any()) } returns reader
        every { reader.plainSize } returns (MarkdownReaderViewModel.MAX_FILE_BYTES + 1).toLong()
        every { reader.close() } returns Unit

        val vm = viewModel()
        vm.load(vaultSource).join()

        assertTrue(vm.state.value is MarkdownPublicationState.Error)
    }

    // ── vaultTreeUri / asset parent directory resolution (RealFile only) ────

    @Test
    fun `load with a vaultTreeUri resolves and stores the asset parent directory`() = runTest {
        val vaultTreeUri = mockk<Uri>(relaxed = true)
        val parentDirectory = mockk<DocumentFile>(relaxed = true)
        every { resolver.openInputStream(parsedUri) } returns "content".byteInputStream()
        every { assetResolver.findParentDirectory(vaultTreeUri, parsedUri) } returns parentDirectory

        val vm = viewModel()
        vm.load(realFileSource, vaultTreeUri).join()

        val state = vm.state.value
        assertTrue(state is MarkdownPublicationState.Ready)
        assertEquals(parentDirectory, (state as MarkdownPublicationState.Ready).assetParentDirectory)
    }

    @Test
    fun `load without a vaultTreeUri never consults the asset resolver`() = runTest {
        every { resolver.openInputStream(parsedUri) } returns "content".byteInputStream()

        val vm = viewModel()
        vm.load(realFileSource, vaultTreeUri = null).join()

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
        every { resolver.openInputStream(parsedUri) } returns threeChapterMarkdown.byteInputStream()
        val vm = viewModel()
        vm.load(realFileSource).join()

        val text = vm.getChapterTextFromProgression(null)

        assertTrue(text!!.contains("First chapter text"), "expected chapter one text, got: $text")
    }

    @Test
    fun `getChapterTextFromProgression anchors to the chapter nearest the given fraction`() = runTest {
        every { resolver.openInputStream(parsedUri) } returns threeChapterMarkdown.byteInputStream()
        val vm = viewModel()
        vm.load(realFileSource).join()

        // 3 chapters; sectionIndexForFraction(0.7, 3) rounds to index 2 (the third).
        val text = vm.getChapterTextFromProgression(0.7)

        assertTrue(text!!.contains("Third chapter text"), "expected chapter three text, got: $text")
    }

    @Test
    fun `getNextChapterText walks forward from the chapter getChapterTextFromProgression anchored`() = runTest {
        every { resolver.openInputStream(parsedUri) } returns threeChapterMarkdown.byteInputStream()
        val vm = viewModel()
        vm.load(realFileSource).join()
        vm.getChapterTextFromProgression(null) // anchors at chapter 0 (Chapter One)

        val next = vm.getNextChapterText()

        assertTrue(next!!.contains("Second chapter text"), "expected chapter two text, got: $next")
    }

    @Test
    fun `getNextChapterText returns null at the end of the document`() = runTest {
        every { resolver.openInputStream(parsedUri) } returns threeChapterMarkdown.byteInputStream()
        val vm = viewModel()
        vm.load(realFileSource).join()
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
        every { resolver.openInputStream(parsedUri) } returns threeChapterMarkdown.byteInputStream()
        val vm = viewModel()
        vm.load(realFileSource).join()

        vm.getChapterTextFromProgression(null)

        assertEquals(3, vm.ttsChapterCount)
        assertEquals(0, vm.ttsChapterIndex)
    }

    @Test
    fun `getPreviousChapterText walks back and updates ttsChapterIndex`() = runTest {
        every { resolver.openInputStream(parsedUri) } returns threeChapterMarkdown.byteInputStream()
        val vm = viewModel()
        vm.load(realFileSource).join()
        vm.getChapterTextFromProgression(0.99) // anchors at chapter 2 (Chapter Three)
        assertEquals(2, vm.ttsChapterIndex)

        val previous = vm.getPreviousChapterText()

        assertTrue(previous!!.contains("Second chapter text"), "expected chapter two text, got: $previous")
        assertEquals(1, vm.ttsChapterIndex)
    }

    @Test
    fun `getPreviousChapterText returns null and leaves the cursor unmoved at the start`() = runTest {
        every { resolver.openInputStream(parsedUri) } returns threeChapterMarkdown.byteInputStream()
        val vm = viewModel()
        vm.load(realFileSource).join()
        vm.getChapterTextFromProgression(null) // anchors at chapter 0

        val previous = vm.getPreviousChapterText()

        assertNull(previous)
        assertEquals(0, vm.ttsChapterIndex)
    }
}
