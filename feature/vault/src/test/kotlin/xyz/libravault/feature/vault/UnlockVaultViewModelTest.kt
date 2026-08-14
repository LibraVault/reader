package xyz.libravault.feature.vault

import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.libravault.core.vaultstore.UnlockOutcome
import xyz.libravault.core.vaultstore.VaultRegistryEntryDto
import java.security.SecureRandom

class UnlockVaultViewModelTest {

    private val sessionManager = mockk<VaultSessionManager>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        coEvery { sessionManager.listVaults() } returns listOf(VaultRegistryEntryDto("vault-1", "Personal", 0L))
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = UnlockVaultViewModel(sessionManager, SavedStateHandle(mapOf("vaultId" to "vault-1")))

    @Test
    fun `loads the vault's display name from the registry`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals("Personal", vm.uiState.value.displayName)
    }

    @Test
    fun `correct PIN sets isUnlocked`() = runTest {
        coEvery { sessionManager.unlockWithPin("vault-1", any()) } returns UnlockOutcome.Success

        val vm = viewModel()
        vm.onPinChanged("1234")
        vm.onUnlockWithPinSubmitted()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isUnlocked)
    }

    @Test
    fun `wrong PIN clears the field and shows an error, does not unlock`() = runTest {
        coEvery { sessionManager.unlockWithPin("vault-1", any()) } returns UnlockOutcome.WrongCredential

        val vm = viewModel()
        vm.onPinChanged("0000")
        vm.onUnlockWithPinSubmitted()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isUnlocked)
        assertEquals("", state.pin)
        assertNotNull(state.errorMessage)
    }

    @Test
    fun `throttled outcome records the countdown, no error message`() = runTest {
        coEvery { sessionManager.unlockWithPin("vault-1", any()) } returns UnlockOutcome.Throttled(5_000L)

        val vm = viewModel()
        vm.onPinChanged("0000")
        vm.onUnlockWithPinSubmitted()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isUnlocked)
        assertEquals(5_000L, state.throttleRemainingMillisAtReport)
        assertNotNull(state.throttleReportedAtEpochMillis)
    }

    @Test
    fun `KeystoreKeyLost force-switches to recovery-key mode`() = runTest {
        coEvery { sessionManager.unlockWithPin("vault-1", any()) } returns UnlockOutcome.KeystoreKeyLost

        val vm = viewModel()
        vm.onPinChanged("1234")
        vm.onUnlockWithPinSubmitted()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(UnlockMode.RECOVERY_KEY, state.mode)
        assertTrue(state.keystoreKeyLost)
    }

    @Test
    fun `recovery key unlock rejects malformed input before ever calling the session manager`() = runTest {
        val vm = viewModel()
        vm.onSwitchMode(UnlockMode.RECOVERY_KEY)
        vm.onRecoveryKeyInputChanged("not a valid key")

        vm.onUnlockWithRecoveryKeySubmitted()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isUnlocked)
        assertNotNull(vm.uiState.value.errorMessage)
        io.mockk.coVerify(exactly = 0) { sessionManager.unlockWithRecoveryKey(any(), any()) }
    }

    @Test
    fun `valid recovery key unlocks`() = runTest {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        coEvery { sessionManager.unlockWithRecoveryKey("vault-1", any()) } returns UnlockOutcome.Success

        val vm = viewModel()
        vm.onSwitchMode(UnlockMode.RECOVERY_KEY)
        vm.onRecoveryKeyInputChanged(RecoveryKeyFormat.toDisplayString(key))

        vm.onUnlockWithRecoveryKeySubmitted()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isUnlocked)
    }
}
