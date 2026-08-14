package xyz.libravault.feature.vault

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.libravault.core.vaultstore.HardwareKeyWrapFactory
import xyz.libravault.core.vaultstore.KeystoreHardwareUnavailableException
import xyz.libravault.core.vaultstore.UnlockOutcome
import xyz.libravault.core.vaultstore.VaultRegistry
import xyz.libravault.core.vaultstore.VaultRegistryEntryDto
import xyz.libravault.core.vaultstore.VaultStore
import xyz.libravault.core.vaultstore.di.VaultsRootDir
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of [VaultSessionManager.createVault]. */
sealed class CreateVaultResult {
    /** @param recoveryKey show this to the user exactly once — see [VaultStore.create]. */
    data class Success(val id: String, val recoveryKey: ByteArray) : CreateVaultResult()

    /** No hardware-backed Keystore on this device (PRD §7.1) — a 4-digit PIN
     * isn't defensible here. The caller should require a longer passphrase
     * instead of retrying with the same PIN, or refuse to create the vault. */
    object HardwareUnavailable : CreateVaultResult()
}

/**
 * App-wide entry point for Encrypted Vaults: owns the [VaultRegistry] (which
 * vaults exist) plus one [VaultStore] instance per vault the user has
 * touched this session (locked or unlocked).
 *
 * A single [Mutex] serializes every vault operation across the whole
 * manager, not per-vault. Simpler than a per-vault lock map, and cheap here:
 * these are all user-tap-driven, one-at-a-time operations, not a hot path —
 * matches [VaultStore]'s own doc comment, which expects exactly this kind of
 * "single-writer boundary" wrapper.
 *
 * Auto-locks every open vault the moment the *app* (not just one screen)
 * leaves the foreground (PRD §7: "default: immediately on backgrounding the
 * app"), via [ProcessLifecycleOwner] rather than any one Activity's
 * onStop — this is a single-activity app today, but tying vault security to
 * "whichever Activity happens to host the current screen" would be a latent
 * bug waiting for a second Activity to appear.
 */
@Singleton
class VaultSessionManager @Inject constructor(
    @VaultsRootDir private val rootDir: File,
    private val keyWrapFactory: HardwareKeyWrapFactory,
) {

    private val mutex = Mutex()
    private val stores = mutableMapOf<String, VaultStore>()

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) = lockAll()
            },
        )
    }

    suspend fun listVaults(): List<VaultRegistryEntryDto> = withContext(Dispatchers.IO) {
        VaultRegistry.list(rootDir)
    }

    fun isUnlocked(id: String): Boolean = stores[id]?.isUnlocked == true

    /** Creates a brand-new vault, registers it, and leaves it unlocked. */
    suspend fun createVault(displayName: String, pin: CharArray): CreateVaultResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val store = storeFor(id)
            try {
                val recoveryKey = store.create(pin)
                VaultRegistry.add(rootDir, VaultRegistryEntryDto(id, displayName, System.currentTimeMillis()))
                CreateVaultResult.Success(id, recoveryKey)
            } catch (e: KeystoreHardwareUnavailableException) {
                // Nothing was registered yet, and VaultStore.create's own catch
                // block already deleted the half-created vault directory — just
                // drop our in-memory reference so a retry starts clean.
                stores.remove(id)
                CreateVaultResult.HardwareUnavailable
            }
        }
    }

    suspend fun unlockWithPin(id: String, pin: CharArray): UnlockOutcome = mutex.withLock {
        storeFor(id).unlockWithPin(pin)
    }

    suspend fun unlockWithRecoveryKey(id: String, recoveryKey: ByteArray): UnlockOutcome = mutex.withLock {
        storeFor(id).unlockWithRecoveryKey(recoveryKey)
    }

    fun lock(id: String) {
        stores[id]?.lock()
    }

    fun lockAll() {
        stores.values.forEach { it.lock() }
    }

    /** The unlocked [VaultStore] for [id], for callers that already checked
     * [isUnlocked] (e.g. the vault-contents screen, Phase 5b). */
    fun requireUnlocked(id: String): VaultStore =
        stores[id]?.takeIf { it.isUnlocked } ?: error("Vault $id is not unlocked")

    private fun storeFor(id: String): VaultStore = stores.getOrPut(id) {
        VaultStore(VaultRegistry.vaultDir(rootDir, id), keystoreAliasFor(id), keyWrapFactory)
    }

    private fun keystoreAliasFor(id: String) = "libravault_vault_$id"
}
