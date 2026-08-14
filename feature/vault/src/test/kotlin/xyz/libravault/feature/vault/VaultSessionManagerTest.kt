package xyz.libravault.feature.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.vaultstore.UnlockOutcome
import xyz.libravault.feature.vault.testing.FakeHardwareKeyWrapFactory
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory

/**
 * Robolectric-hosted (not plain JVM) specifically because
 * [VaultSessionManager]'s constructor touches `ProcessLifecycleOwner`, a real
 * Android framework class. The auto-lock-on-background *wiring* itself
 * (`ProcessLifecycleOwner`'s `ON_STOP` actually firing) isn't exercised here —
 * driving a real Activity through that transition under Robolectric adds a
 * lot of test-only machinery to verify one `lockAll()` call, and [lockAll] is
 * already covered directly below. Matches core:vaultcontent's Phase 3
 * decision to validate real device-only behavior once (there, in the Phase 0
 * spike) rather than fake it repeatedly in unit tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class VaultSessionManagerTest {

    private lateinit var rootDir: File
    private lateinit var keyWrapFactory: FakeHardwareKeyWrapFactory
    private lateinit var manager: VaultSessionManager

    @Before
    fun setUp() {
        rootDir = createTempDirectory("vault-session-test").toFile()
        keyWrapFactory = FakeHardwareKeyWrapFactory()
        manager = VaultSessionManager(rootDir, keyWrapFactory)
    }

    @Test
    fun `createVault registers the vault and leaves it unlocked`() = runBlocking {
        val result = manager.createVault("Personal", "1234".toCharArray())

        check(result is CreateVaultResult.Success)
        assertEquals(32, result.recoveryKey.size)
        assertTrue(manager.isUnlocked(result.id))
        assertEquals(listOf("Personal"), manager.listVaults().map { it.displayName })
    }

    @Test
    fun `createVault surfaces HardwareUnavailable and does not register anything`() = runBlocking {
        keyWrapFactory.simulateHardwareUnavailable = true

        val result = manager.createVault("Personal", "1234".toCharArray())

        assertEquals(CreateVaultResult.HardwareUnavailable, result)
        assertTrue(manager.listVaults().isEmpty())
    }

    @Test
    fun `lock drops unlocked state, correct PIN unlocks again`() = runBlocking {
        val created = manager.createVault("Personal", "1234".toCharArray()) as CreateVaultResult.Success
        manager.lock(created.id)
        assertFalse(manager.isUnlocked(created.id))

        val outcome = manager.unlockWithPin(created.id, "1234".toCharArray())

        assertEquals(UnlockOutcome.Success, outcome)
        assertTrue(manager.isUnlocked(created.id))
    }

    @Test
    fun `wrong PIN does not unlock`() = runBlocking {
        val created = manager.createVault("Personal", "1234".toCharArray()) as CreateVaultResult.Success
        manager.lock(created.id)

        val outcome = manager.unlockWithPin(created.id, "0000".toCharArray())

        assertEquals(UnlockOutcome.WrongCredential, outcome)
        assertFalse(manager.isUnlocked(created.id))
    }

    @Test
    fun `recovery key unlocks after the Keystore key is lost`() = runBlocking {
        val created = manager.createVault("Personal", "1234".toCharArray()) as CreateVaultResult.Success
        val recoveryKey = created.recoveryKey
        manager.lock(created.id)
        keyWrapFactory.forgetKey("libravault_vault_${created.id}")

        val pinOutcome = manager.unlockWithPin(created.id, "1234".toCharArray())
        assertEquals(UnlockOutcome.KeystoreKeyLost, pinOutcome)

        val recoveryOutcome = manager.unlockWithRecoveryKey(created.id, recoveryKey)
        assertEquals(UnlockOutcome.Success, recoveryOutcome)
        assertTrue(manager.isUnlocked(created.id))
    }

    @Test
    fun `lockAll locks every vault this session has touched`() = runBlocking {
        val first = manager.createVault("First", "1111".toCharArray()) as CreateVaultResult.Success
        val second = manager.createVault("Second", "2222".toCharArray()) as CreateVaultResult.Success

        manager.lockAll()

        assertFalse(manager.isUnlocked(first.id))
        assertFalse(manager.isUnlocked(second.id))
    }

    @Test
    fun `requireUnlocked throws for a locked vault`() = runBlocking {
        val created = manager.createVault("Personal", "1234".toCharArray()) as CreateVaultResult.Success
        manager.lock(created.id)

        assertThrows(IllegalStateException::class.java) { manager.requireUnlocked(created.id) }
        Unit
    }

    @Test
    fun `isUnlocked is false for an id the manager has never seen`() {
        assertFalse(manager.isUnlocked("unknown-id"))
    }
}
