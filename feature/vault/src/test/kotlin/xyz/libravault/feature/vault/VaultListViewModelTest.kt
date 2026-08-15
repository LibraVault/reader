package xyz.libravault.feature.vault

import android.content.Context
import android.content.SharedPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.libravault.core.storage.LibravaultPreferences
import xyz.libravault.core.vaultstore.VaultRegistryEntryDto
import xyz.libravault.core.vaultstore.VaultSessionManager

class VaultListViewModelTest {

    private val sessionManager = mockk<VaultSessionManager>()
    private val context = mockk<Context>()
    private val prefs = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { context.getSharedPreferences(LibravaultPreferences.FILE_NAME, Context.MODE_PRIVATE) } returns prefs
        every { prefs.edit() } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        // Default: explainer already dismissed, so tests focused on vault
        // listing/locking don't also need to stub this every time.
        every { prefs.getBoolean(LibravaultPreferences.KEY_VAULT_EXPLAINER_SHOWN, false) } returns true
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = VaultListViewModel(sessionManager, context)

    // ── vault listing / locking ─────────────────────────────────────────────

    @Test
    fun `maps registry entries to unlocked state per vault`() = runTest {
        coEvery { sessionManager.listVaults() } returns listOf(
            VaultRegistryEntryDto("a", "Locked One", 0L),
            VaultRegistryEntryDto("b", "Open One", 1L),
        )
        every { sessionManager.isUnlocked("a") } returns false
        every { sessionManager.isUnlocked("b") } returns true

        val vm = viewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(
            listOf(
                VaultListItemUiState("a", "Locked One", false),
                VaultListItemUiState("b", "Open One", true),
            ),
            state.vaults,
        )
    }

    @Test
    fun `lock delegates to the session manager and refreshes`() = runTest {
        coEvery { sessionManager.listVaults() } returns listOf(VaultRegistryEntryDto("a", "Personal", 0L))
        every { sessionManager.isUnlocked("a") } returns true
        every { sessionManager.lock("a") } returns Unit

        val vm = viewModel()
        advanceUntilIdle()

        vm.lock("a")
        advanceUntilIdle()

        verify { sessionManager.lock("a") }
    }

    @Test
    fun `starts in a loading state before the first refresh completes`() {
        coEvery { sessionManager.listVaults() } returns emptyList()
        // No advanceUntilIdle() — asserting the synchronous initial value.
        val vm = viewModel()

        assertTrue(vm.uiState.value.isLoading)
    }

    // ── Folder-vs-Vault explainer ───────────────────────────────────────────

    @Test
    fun `showExplainer is true when the flag was never set`() = runTest {
        every { prefs.getBoolean(LibravaultPreferences.KEY_VAULT_EXPLAINER_SHOWN, false) } returns false
        coEvery { sessionManager.listVaults() } returns emptyList()

        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.showExplainer)
    }

    @Test
    fun `showExplainer is false once the flag has been set`() = runTest {
        coEvery { sessionManager.listVaults() } returns emptyList()

        val vm = viewModel()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.showExplainer)
    }

    @Test
    fun `dismissExplainer persists the flag and clears the state`() = runTest {
        every { prefs.getBoolean(LibravaultPreferences.KEY_VAULT_EXPLAINER_SHOWN, false) } returns false
        coEvery { sessionManager.listVaults() } returns emptyList()
        val vm = viewModel()
        advanceUntilIdle()

        vm.dismissExplainer()

        assertFalse(vm.uiState.value.showExplainer)
        verify { editor.putBoolean(LibravaultPreferences.KEY_VAULT_EXPLAINER_SHOWN, true) }
        verify { editor.apply() }
    }

    @Test
    fun `refresh does not clobber the explainer state`() = runTest {
        every { prefs.getBoolean(LibravaultPreferences.KEY_VAULT_EXPLAINER_SHOWN, false) } returns false
        every { sessionManager.isUnlocked("a") } returns true
        coEvery { sessionManager.listVaults() } returns listOf(VaultRegistryEntryDto("a", "Personal", 0L))

        val vm = viewModel()
        advanceUntilIdle()
        vm.refresh()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.showExplainer)
        assertEquals(1, state.vaults.size)
    }
}
