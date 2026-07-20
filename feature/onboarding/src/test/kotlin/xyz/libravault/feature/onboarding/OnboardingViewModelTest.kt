package xyz.libravault.feature.onboarding

import android.net.Uri
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.libravault.core.domain.model.VaultFolder
import xyz.libravault.core.domain.usecase.AddVaultFolderUseCase
import xyz.libravault.core.logger.LibravaultLogger
import xyz.libravault.core.storage.VaultManager

class OnboardingViewModelTest {

    private val addVaultFolder = mockk<AddVaultFolderUseCase>()
    private val vaultManager   = mockk<VaultManager>()
    private val logger         = mockk<LibravaultLogger>(relaxed = true)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = OnboardingViewModel(
        addVaultFolder = addVaultFolder,
        vaultManager   = vaultManager,
        logger         = logger,
    )

    // ── Folder picking ───────────────────────────────────────────────────────

    @Test
    fun `onFolderPicked success sets isLoading true then false`() = runTest {
        val vault = VaultFolder(1L, "content://vault", "My Vault")
        coEvery { addVaultFolder("content://vault", "My Vault") } returns vault
        val vm = viewModel()

        vm.uiState.test {
            val initial = awaitItem()
            assertFalse(initial.isLoading)

            vm.onFolderPicked(Uri.parse("content://vault"), "My Vault")

            val loading = awaitItem()
            assertTrue(loading.isLoading)
            assertNull(loading.error)

            val done = awaitItem()
            assertFalse(done.isLoading)
            assertNull(done.error)
            assertEquals(listOf("My Vault"), done.addedVaultNames)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onFolderPicked persists permission before adding vault`() = runTest {
        val vault = VaultFolder(1L, "content://vault", "Test")
        coEvery { addVaultFolder("content://vault", "Test") } returns vault
        val vm = viewModel()

        vm.onFolderPicked(Uri.parse("content://vault"), "Test")

        coVerify { vaultManager.persistPermission(Uri.parse("content://vault")) }
        coVerify { addVaultFolder("content://vault", "Test") }
    }

    @Test
    fun `onFolderPicked appends vault name to list on success`() = runTest {
        val vault1 = VaultFolder(1L, "uri1", "Vault 1")
        val vault2 = VaultFolder(2L, "uri2", "Vault 2")

        coEvery { addVaultFolder("uri1", "Vault 1") } returns vault1
        coEvery { addVaultFolder("uri2", "Vault 2") } returns vault2

        val vm = viewModel()

        vm.uiState.test {
            val initial = awaitItem()
            assertEquals(0, initial.addedVaultNames.size)

            vm.onFolderPicked(Uri.parse("uri1"), "Vault 1")
            awaitItem() // loading
            val after1 = awaitItem()
            assertEquals(listOf("Vault 1"), after1.addedVaultNames)

            vm.onFolderPicked(Uri.parse("uri2"), "Vault 2")
            awaitItem() // loading
            val after2 = awaitItem()
            assertEquals(listOf("Vault 1", "Vault 2"), after2.addedVaultNames)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onFolderPicked failure sets error message`() = runTest {
        val exception = RuntimeException("Disk full")
        coEvery { addVaultFolder("uri", "Vault") } throws exception
        val vm = viewModel()

        vm.uiState.test {
            val initial = awaitItem()
            assertNull(initial.error)

            vm.onFolderPicked(Uri.parse("uri"), "Vault")

            awaitItem() // loading
            val error = awaitItem()
            assertFalse(error.isLoading)
            assertEquals("Disk full", error.error)
            assertEquals(0, error.addedVaultNames.size) // unchanged
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onFolderPicked failure preserves previously added vaults`() = runTest {
        val vault1 = VaultFolder(1L, "uri1", "Vault 1")
        coEvery { addVaultFolder("uri1", "Vault 1") } returns vault1
        coEvery { addVaultFolder("uri2", "Vault 2") } throws RuntimeException("Failed")

        val vm = viewModel()

        vm.uiState.test {
            awaitItem() // initial

            vm.onFolderPicked(Uri.parse("uri1"), "Vault 1")
            awaitItem() // loading
            val after1 = awaitItem()
            assertEquals(1, after1.addedVaultNames.size)

            vm.onFolderPicked(Uri.parse("uri2"), "Vault 2")
            awaitItem() // loading
            val afterError = awaitItem()
            assertEquals(1, afterError.addedVaultNames.size) // still just Vault 1
            assertEquals("Failed", afterError.error)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
