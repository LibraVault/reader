package xyz.libravault.feature.vault

import io.mockk.coEvery
import io.mockk.coVerify
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CreateVaultViewModelTest {

    private val sessionManager = mockk<VaultSessionManager>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        coEvery { sessionManager.listVaults() } returns emptyList()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = CreateVaultViewModel(sessionManager)

    @Test
    fun `initial state suggests a default name from the existing vault count`() = runTest {
        coEvery { sessionManager.listVaults() } returns listOf(
            fakeEntry("a"), fakeEntry("b"),
        )
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals("Vault 3", vm.uiState.value.displayName)
    }

    @Test
    fun `onNameConfirmed refuses to advance on a blank name`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onDisplayNameChanged("   ")

        vm.onNameConfirmed()

        assertEquals(CreateVaultStep.NAME, vm.uiState.value.step)
    }

    @Test
    fun `onNameConfirmed advances to PIN with a real name`() = runTest {
        val vm = viewModel()
        vm.onDisplayNameChanged("Personal")

        vm.onNameConfirmed()

        assertEquals(CreateVaultStep.PIN, vm.uiState.value.step)
    }

    @Test
    fun `onPinSubmitted rejects a PIN shorter than the minimum`() = runTest {
        val vm = viewModel()
        vm.onDisplayNameChanged("Personal")
        vm.onNameConfirmed()
        vm.onPinChanged("12")

        vm.onPinSubmitted()

        assertEquals(CreateVaultStep.PIN, vm.uiState.value.step)
        assertNotNull(vm.uiState.value.pinError)
    }

    @Test
    fun `confirm PIN mismatch shows an error and clears the confirm field, not the vault`() = runTest {
        val vm = viewModel()
        vm.onDisplayNameChanged("Personal")
        vm.onNameConfirmed()
        vm.onPinChanged("1234")
        vm.onPinSubmitted()
        vm.onConfirmPinChanged("0000")

        vm.onConfirmPinSubmitted()

        assertEquals(CreateVaultStep.CONFIRM_PIN, vm.uiState.value.step)
        assertEquals("", vm.uiState.value.confirmPin)
        assertNotNull(vm.uiState.value.pinError)
        coVerify(exactly = 0) { sessionManager.createVault(any(), any()) }
    }

    @Test
    fun `matching PIN confirmation creates the vault and shows the recovery key`() = runTest {
        val recoveryKey = ByteArray(32) { it.toByte() }
        // The ViewModel zeroes CreateVaultResult.Success.recoveryKey in place
        // once it's captured the display string — compute the expected value
        // up front so this array-aliasing side effect doesn't race the assertion.
        val expectedDisplay = RecoveryKeyFormat.toDisplayString(recoveryKey)
        coEvery { sessionManager.createVault("Personal", any()) } returns
            CreateVaultResult.Success("vault-1", recoveryKey)

        val vm = viewModel()
        vm.onDisplayNameChanged("Personal")
        vm.onNameConfirmed()
        vm.onPinChanged("1234")
        vm.onPinSubmitted()
        vm.onConfirmPinChanged("1234")

        vm.onConfirmPinSubmitted()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(CreateVaultStep.RECOVERY_KEY, state.step)
        assertEquals("vault-1", state.createdVaultId)
        assertNotNull(state.recoveryKeyDisplay)
        assertEquals(expectedDisplay, state.recoveryKeyDisplay)
        // The ViewModel's own copy of the PIN was cleared once consumed.
        assertEquals("", state.pin)
        assertEquals("", state.confirmPin)
    }

    @Test
    fun `HardwareUnavailable surfaces an error and returns to the PIN step`() = runTest {
        coEvery { sessionManager.createVault(any(), any()) } returns CreateVaultResult.HardwareUnavailable

        val vm = viewModel()
        vm.onDisplayNameChanged("Personal")
        vm.onNameConfirmed()
        vm.onPinChanged("1234")
        vm.onPinSubmitted()
        vm.onConfirmPinChanged("1234")

        vm.onConfirmPinSubmitted()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(CreateVaultStep.PIN, state.step)
        assertNotNull(state.creationError)
        assertNull(state.createdVaultId)
    }

    @Test
    fun `onBack from CONFIRM_PIN returns to PIN and clears the confirm field`() = runTest {
        val vm = viewModel()
        vm.onDisplayNameChanged("Personal")
        vm.onNameConfirmed()
        vm.onPinChanged("1234")
        vm.onPinSubmitted()
        vm.onConfirmPinChanged("12")

        vm.onBack()

        assertEquals(CreateVaultStep.PIN, vm.uiState.value.step)
        assertEquals("", vm.uiState.value.confirmPin)
    }

    @Test
    fun `onBack from RECOVERY_KEY is a no-op — no way back once the vault exists`() = runTest {
        val recoveryKey = ByteArray(32)
        coEvery { sessionManager.createVault(any(), any()) } returns CreateVaultResult.Success("id", recoveryKey)
        val vm = viewModel()
        vm.onDisplayNameChanged("Personal")
        vm.onNameConfirmed()
        vm.onPinChanged("1234")
        vm.onPinSubmitted()
        vm.onConfirmPinChanged("1234")
        vm.onConfirmPinSubmitted()
        advanceUntilIdle()

        vm.onBack()

        assertEquals(CreateVaultStep.RECOVERY_KEY, vm.uiState.value.step)
    }

    private fun fakeEntry(id: String) =
        xyz.libravault.core.vaultstore.VaultRegistryEntryDto(id, "Vault $id", 0L)
}
