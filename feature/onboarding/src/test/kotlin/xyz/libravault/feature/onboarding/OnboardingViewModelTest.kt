package xyz.libravault.feature.onboarding

import android.net.Uri
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.libravault.core.domain.model.VaultFolder
import xyz.libravault.core.domain.usecase.AddVaultFolderUseCase
import xyz.libravault.core.logger.LibravaultLogger
import xyz.libravault.core.storage.VaultManager

class OnboardingViewModelTest {

    private val addVaultFolder = mockk<AddVaultFolderUseCase>()
    private val vaultManager = mockk<VaultManager>(relaxed = true)
    private val logger = mockk<LibravaultLogger>(relaxed = true)

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = OnboardingViewModel(addVaultFolder, vaultManager, logger)

    @Test
    fun `onFolderPicked persists permission before saving the vault`() = runTest(mainDispatcher) {
        val uri = mockk<Uri>(relaxed = true)
        coEvery { addVaultFolder(any(), "Books") } returns VaultFolder(id = 1, uri = "content://x", displayName = "Books")

        viewModel().onFolderPicked(uri, "Books")

        verify { vaultManager.persistPermission(uri) }
    }

    @Test
    fun `onFolderPicked success adds the vault name and clears loading`() = runTest(mainDispatcher) {
        val uri = mockk<Uri>(relaxed = true)
        coEvery { addVaultFolder(any(), "Books") } returns VaultFolder(id = 1, uri = "content://x", displayName = "Books")

        val vm = viewModel()
        vm.onFolderPicked(uri, "Books")

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(listOf("Books"), state.addedVaultNames)
        assertNull(state.error)
    }

    @Test
    fun `onFolderPicked accumulates multiple vault names across calls`() = runTest(mainDispatcher) {
        val uri = mockk<Uri>(relaxed = true)
        coEvery { addVaultFolder(any(), "Books") } returns VaultFolder(id = 1, uri = "content://x", displayName = "Books")
        coEvery { addVaultFolder(any(), "Audiobooks") } returns VaultFolder(id = 2, uri = "content://y", displayName = "Audiobooks")

        val vm = viewModel()
        vm.onFolderPicked(uri, "Books")
        vm.onFolderPicked(uri, "Audiobooks")

        assertEquals(listOf("Books", "Audiobooks"), vm.uiState.value.addedVaultNames)
    }

    @Test
    fun `onFolderPicked failure surfaces the error and clears loading without adding a name`() = runTest(mainDispatcher) {
        val uri = mockk<Uri>(relaxed = true)
        coEvery { addVaultFolder(any(), "Books") } throws RuntimeException("permission denied")

        val vm = viewModel()
        vm.onFolderPicked(uri, "Books")

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(emptyList<String>(), state.addedVaultNames)
        assertEquals("permission denied", state.error)
    }
}
