package xyz.libravault.core.domain.usecase

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.libravault.core.domain.model.VaultFolder
import xyz.libravault.core.domain.repository.VaultRepository

class AddVaultFolderUseCaseTest {

    private val vaultRepository = mockk<VaultRepository>()
    private val useCase = AddVaultFolderUseCase(vaultRepository)

    // ── Adding new vaults ────────────────────────────────────────────────────

    @Test
    fun `invoke adds new vault when URI not already registered`() = runTest {
        val newVault = VaultFolder(1L, "content://new", "New Vault")
        coEvery { vaultRepository.findByUri("content://new") } returns null
        coEvery { vaultRepository.addVault("content://new", "New Vault") } returns newVault

        val result = useCase("content://new", "New Vault")

        assertEquals(newVault, result)
        coVerify { vaultRepository.addVault("content://new", "New Vault") }
    }

    // ── Deduplication ────────────────────────────────────────────────────────

    @Test
    fun `invoke returns existing vault without calling addVault when URI already registered`() = runTest {
        val existing = VaultFolder(1L, "content://existing", "My Vault")
        coEvery { vaultRepository.findByUri("content://existing") } returns existing

        val result = useCase("content://existing", "My Vault")

        assertEquals(existing, result)
        coVerify(exactly = 0) { vaultRepository.addVault(any(), any()) }
    }

    @Test
    fun `invoke ignores displayName parameter when vault exists`() = runTest {
        val existing = VaultFolder(1L, "content://vault", "Original Name")
        coEvery { vaultRepository.findByUri("content://vault") } returns existing

        val result = useCase("content://vault", "Different Name")

        assertEquals("Original Name", result.displayName)
    }
}
