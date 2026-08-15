package xyz.libravault.feature.vault

import android.content.Context
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.libravault.core.vaultstore.VaultSessionManager
import xyz.libravault.core.vaultstore.VaultStore

/**
 * Only the pre-playback error paths are unit-testable here — everything past
 * them constructs a real `ExoPlayer` (`ExoPlayer.Builder(context).build()`),
 * which needs a real Android runtime. Matches core:vaultcontent's Phase 3
 * decision not to fake device-only behavior in unit tests.
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
}
