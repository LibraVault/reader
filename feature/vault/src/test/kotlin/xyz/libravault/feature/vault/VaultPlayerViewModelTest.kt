package xyz.libravault.feature.vault

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.libravault.core.vaultstore.VaultBookmark
import xyz.libravault.core.vaultstore.VaultSessionManager
import xyz.libravault.core.vaultstore.VaultStore
import xyz.libravault.core.vaultstore.toHexString

/**
 * Only the pre-playback error paths are unit-testable here — everything past
 * them constructs a real `ExoPlayer` (`ExoPlayer.Builder(context).build()`),
 * which needs a real Android runtime. Matches core:vaultcontent's Phase 3
 * decision not to fake device-only behavior in unit tests.
 *
 * Bookmark methods (`addBookmark`/`removeBookmark`/`updateBookmarkNote`) only
 * depend on the `VaultStore` reference and `fileId` — both are set before the
 * entry lookup, same as `VaultReaderViewModel` — so they're exercisable via
 * the "unknown fileId" setup below without needing a real ExoPlayer.
 */
class VaultPlayerViewModelTest {

    private val sessionManager = mockk<VaultSessionManager>()
    private val vaultStore = mockk<VaultStore>()
    private val context = mockk<Context>()
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

    private fun viewModel() = VaultPlayerViewModel(
        sessionManager, context,
        SavedStateHandle(mapOf("vaultId" to "vault-1", "fileId" to fileIdHex)),
    )

    @Test
    fun `locked vault surfaces an error, no ExoPlayer constructed`() = runTest {
        every { sessionManager.isUnlocked("vault-1") } returns false

        val vm = viewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.isLoading.not())
        assertNotNull(state.error)
        assertEquals("Vault is locked", state.error)
    }

    @Test
    fun `unknown fileId surfaces an error`() = runTest {
        every { sessionManager.isUnlocked("vault-1") } returns true
        coEvery { vaultStore.listEntries() } returns emptyList()

        val vm = viewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNotNull(state.error)
        assertEquals("File not found in this vault", state.error)
    }

    @Test
    fun `addBookmark is a no-op before the vault store is reachable`() = runTest {
        every { sessionManager.isUnlocked("vault-1") } returns false

        val vm = viewModel()
        vm.addBookmark()
        advanceUntilIdle()

        coVerify(exactly = 0) { vaultStore.addBookmark(any(), any(), any(), any()) }
        assertTrue(vm.bookmarks.value.isEmpty())
    }

    @Test
    fun `addBookmark stores a ms-prefixed positionRef for the current position and appends to state`() = runTest {
        every { sessionManager.isUnlocked("vault-1") } returns true
        coEvery { vaultStore.listEntries() } returns emptyList()
        val stored = VaultBookmark(id = 7L, positionRef = "ms:0", label = "0:00", createdAtEpochMillis = 0L)
        coEvery { vaultStore.addBookmark(fileId, "ms:0", "0:00", null) } returns stored

        val vm = viewModel()
        advanceUntilIdle()
        vm.addBookmark()
        advanceUntilIdle()

        coVerify { vaultStore.addBookmark(fileId, "ms:0", "0:00", null) }
        assertEquals(listOf(stored), vm.bookmarks.value)
    }

    @Test
    fun `removeBookmark removes exactly the targeted bookmark from state`() = runTest {
        every { sessionManager.isUnlocked("vault-1") } returns true
        coEvery { vaultStore.listEntries() } returns emptyList()
        val keep = VaultBookmark(id = 1L, positionRef = "ms:1000", createdAtEpochMillis = 0L)
        val toStore = VaultBookmark(id = 2L, positionRef = "ms:2000", label = "0:02", createdAtEpochMillis = 0L)
        coEvery { vaultStore.addBookmark(fileId, "ms:0", "0:00", null) } returns keep
        coEvery { vaultStore.addBookmark(fileId, "ms:0", "0:02", null) } returns toStore
        coEvery { vaultStore.removeBookmark(fileId, 2L) } returns Unit

        val vm = viewModel()
        advanceUntilIdle()
        vm.addBookmark()
        vm.addBookmark("0:02")
        advanceUntilIdle()
        vm.removeBookmark(2L)
        advanceUntilIdle()

        assertEquals(listOf(keep), vm.bookmarks.value)
    }

    @Test
    fun `updateBookmarkNote updates exactly the targeted bookmark in state`() = runTest {
        every { sessionManager.isUnlocked("vault-1") } returns true
        coEvery { vaultStore.listEntries() } returns emptyList()
        val stored = VaultBookmark(id = 3L, positionRef = "ms:0", label = "0:00", createdAtEpochMillis = 0L)
        coEvery { vaultStore.addBookmark(fileId, "ms:0", "0:00", null) } returns stored
        coEvery { vaultStore.updateBookmarkNote(fileId, 3L, "note") } returns Unit

        val vm = viewModel()
        advanceUntilIdle()
        vm.addBookmark()
        advanceUntilIdle()
        vm.updateBookmarkNote(3L, "note")
        advanceUntilIdle()

        assertEquals("note", vm.bookmarks.value.single { it.id == 3L }.note)
    }

    @Test
    fun `seekToBookmark parses the ms prefix into positionMs`() = runTest {
        every { sessionManager.isUnlocked("vault-1") } returns false

        val vm = viewModel()
        vm.seekToBookmark(VaultBookmark(id = 1L, positionRef = "ms:42000", createdAtEpochMillis = 0L))
        advanceUntilIdle()

        assertEquals(42000L, vm.uiState.value.positionMs)
        assertTrue(vm.uiState.value.isPlaying)
    }

    @Test
    fun `seekToBookmark ignores a malformed positionRef`() = runTest {
        every { sessionManager.isUnlocked("vault-1") } returns false

        val vm = viewModel()
        vm.seekToBookmark(VaultBookmark(id = 1L, positionRef = "page:4", createdAtEpochMillis = 0L))
        advanceUntilIdle()

        assertEquals(0L, vm.uiState.value.positionMs)
        assertTrue(vm.uiState.value.isPlaying.not())
    }
}
