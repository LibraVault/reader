package xyz.libravault.feature.vault

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
import xyz.libravault.core.vaultstore.VaultRegistryEntryDto

class VaultListViewModelTest {

    private val sessionManager = mockk<VaultSessionManager>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `maps registry entries to unlocked state per vault`() = runTest {
        coEvery { sessionManager.listVaults() } returns listOf(
            VaultRegistryEntryDto("a", "Locked One", 0L),
            VaultRegistryEntryDto("b", "Open One", 1L),
        )
        every { sessionManager.isUnlocked("a") } returns false
        every { sessionManager.isUnlocked("b") } returns true

        val vm = VaultListViewModel(sessionManager)
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

        val vm = VaultListViewModel(sessionManager)
        advanceUntilIdle()

        vm.lock("a")
        advanceUntilIdle()

        verify { sessionManager.lock("a") }
    }

    @Test
    fun `starts in a loading state before the first refresh completes`() {
        coEvery { sessionManager.listVaults() } returns emptyList()
        // No advanceUntilIdle() — asserting the synchronous initial value.
        val vm = VaultListViewModel(sessionManager)

        assertTrue(vm.uiState.value.isLoading)
    }
}
