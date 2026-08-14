package xyz.libravault.core.vaultstore

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.libravault.core.vaultcrypto.Argon2Params
import xyz.libravault.core.vaultcrypto.ChunkedVaultWriter
import xyz.libravault.core.vaultcrypto.VaultAuthenticationException
import xyz.libravault.core.vaultcrypto.VaultFileReader
import xyz.libravault.core.vaultcrypto.VaultFormat
import xyz.libravault.core.vaultcrypto.VaultKeyManager
import java.io.File
import java.io.InputStream
import java.security.SecureRandom

/** Outcome of an unlock attempt. Deliberately does not distinguish "wrong PIN"
 * from "tampered data" — same reasoning as core:vaultcrypto's
 * [VaultAuthenticationException]: an oracle that tells them apart is itself a
 * side channel. */
sealed class UnlockOutcome {
    /** [VaultStore] is now unlocked. */
    object Success : UnlockOutcome()

    /** Wrong PIN/recovery key, or the persisted key material was tampered with. */
    object WrongCredential : UnlockOutcome()

    /** Too many recent failures — try again in [remainingDelayMillis] (PRD §7). */
    data class Throttled(val remainingDelayMillis: Long) : UnlockOutcome()

    /** The Keystore key is gone (implementation plan §A.4 failure case (c)) —
     * PIN unlock is unavailable on this device; the caller must fall back to
     * [VaultStore.unlockWithRecoveryKey]. This is precisely the scenario the
     * recovery key exists to rescue (implementation plan §A.5). */
    object KeystoreKeyLost : UnlockOutcome()
}

/** Not enough free space to import a file of the declared size — checked
 * before writing anything (PRD Phase 2 scope: "free-space precheck before
 * import"). */
class InsufficientStorageException(requiredBytes: Long, availableBytes: Long) :
    Exception("Not enough free space: need $requiredBytes bytes, have $availableBytes available")

/** [VaultStore.create] called on a directory that already holds a vault. */
class VaultAlreadyExistsException(vaultDir: File) : Exception("A vault already exists at ${vaultDir.path}")

/** A call requiring an unlocked vault was made while locked. */
class VaultLockedException : Exception("Vault is locked")

/**
 * Vault lifecycle: create, unlock (PIN or recovery key), lock, import,
 * list/read manifest entries.
 *
 * One instance per vault directory, **not thread-safe** — matches
 * [VaultFileReader]'s own constraint, since this class holds a single
 * in-memory VMK and delegates content reads to that class. Callers on Android
 * (Phase 5/6) are expected to own one `VaultStore` per open vault behind a
 * single-writer boundary (e.g. a `Mutex`-guarded ViewModel/repository), not
 * share it across concurrent callers.
 *
 * Deliberately named `VaultStore`, not `VaultManager` — `core.storage.VaultManager`
 * already exists for the *unencrypted* "Folder" concept (PRD §9's rename to
 * "Folder" in UI copy hasn't touched that class name yet). Two different
 * classes, two different guarantees; picking a different name here avoids
 * conflating them while that rename is still pending.
 *
 * [baseDir] and [keyWrapFactory] are constructor parameters, not read from an
 * Android `Context` directly, specifically so this class is JVM-testable
 * against a temp directory and [FakeHardwareKeyWrapFactory][
 * xyz.libravault.core.vaultstore.testing.FakeHardwareKeyWrapFactory] — the
 * lesson from `core.licensing.ProStateManager` this module exists not to
 * repeat (implementation plan Phase 1/2 intro).
 */
class VaultStore(
    private val vaultDir: File,
    private val keystoreKeyAlias: String,
    private val keyWrapFactory: HardwareKeyWrapFactory,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    /**
     * Defaults to plain [File.usableSpace] — lint flags this, correctly,
     * suggesting `StorageManager.getAllocatableBytes` instead, which also
     * accounts for clearable cache space the OS could reclaim on request and
     * would give a less conservative (more accurate) precheck. Not used here
     * on purpose: that API needs a `StorageManager`/`Context`, which this
     * class deliberately never takes (see the class doc — JVM-testability is
     * the point). Left as an injectable function specifically so the Android
     * integration layer (Phase 5/6 DI wiring) can supply a
     * `StorageManager`-backed estimator there instead, without this class
     * needing to change.
     */
    private val usableSpaceBytes: () -> Long = { vaultDir.usableSpace },
) {

    @Volatile private var vmk: ByteArray? = null

    val isUnlocked: Boolean get() = vmk != null
    fun exists(): Boolean = VaultConfig.exists(vaultDir)

    /**
     * Creates a brand-new vault and leaves it unlocked (the caller just set
     * the PIN; there is no reason to make them re-enter it immediately).
     *
     * @return the 256-bit recovery key — show it to the user exactly once.
     *   This method does not persist it in recoverable form anywhere; losing
     *   it before the user saves it means losing the vault's only defense
     *   against a lost Keystore key (implementation plan §A.5).
     * @throws VaultAlreadyExistsException [vaultDir] already holds a vault
     * @throws KeystoreHardwareUnavailableException no hardware-backed Keystore
     *   on this device — PRD §7.1: do not fall back to a software key; the
     *   caller should require a passphrase-strength credential instead, or
     *   refuse to create the vault, not silently proceed with weaker
     *   protection than the UI promised.
     */
    suspend fun create(pin: CharArray, argon2Params: Argon2Params = Argon2Params.DEFAULT): ByteArray =
        withContext(Dispatchers.IO) {
            if (exists()) throw VaultAlreadyExistsException(vaultDir)
            vaultDir.mkdirs()

            val newVault = VaultKeyManager.create(pin, argon2Params)
            try {
                val keyWrap = keyWrapFactory.createNew(keystoreKeyAlias)
                val keystoreWrap = keyWrap.wrap(newVault.material.wrappedVmkByKek.toBytes())
                VaultConfig.write(
                    vaultDir = vaultDir,
                    keystoreKeyAlias = keystoreKeyAlias,
                    argon2Salt = newVault.material.argon2Salt,
                    argon2Params = argon2Params,
                    keystoreWrap = keystoreWrap,
                    wrappedVmkByRecovery = newVault.material.wrappedVmkByRecovery,
                )
                VaultManifest.write(vaultDir, newVault.vmk, emptyList())
                vmk = newVault.vmk
                newVault.recoveryKey
            } catch (e: Exception) {
                newVault.vmk.fill(0)
                vaultDir.deleteRecursively()
                throw e
            }
        }

    suspend fun unlockWithPin(pin: CharArray): UnlockOutcome = withContext(Dispatchers.IO) {
        val dto = VaultConfig.read(vaultDir)
        val now = nowEpochMillis()

        val delay = UnlockAttemptThrottle.remainingDelayMillis(dto.failedAttempts, dto.lastAttemptEpochMillis, now)
        if (delay > 0) return@withContext UnlockOutcome.Throttled(delay)

        val keyWrap = try {
            keyWrapFactory.forExisting(dto.keystoreKeyAlias)
        } catch (e: KeystoreKeyLostException) {
            return@withContext UnlockOutcome.KeystoreKeyLost
        }

        val outcome = try {
            val kekWrappedVmk = bytesToWrappedKey(keyWrap.unwrap(VaultConfig.keystoreWrapOf(dto)))
            val unlockedVmk = VaultKeyManager.unlockWithPin(
                pin, dto.argon2SaltB64.fromB64(), VaultConfig.argon2ParamsOf(dto), kekWrappedVmk,
            )
            vmk = unlockedVmk
            UnlockOutcome.Success
        } catch (e: VaultAuthenticationException) {
            UnlockOutcome.WrongCredential
        }

        val newFailedAttempts = if (outcome is UnlockOutcome.Success) 0 else dto.failedAttempts + 1
        VaultConfig.updateThrottleState(vaultDir, newFailedAttempts, now)
        outcome
    }

    /**
     * Deliberately independent of the Keystore layer and the PIN throttle —
     * must keep working even if both are broken, since that's the entire
     * justification for this path existing (implementation plan §A.5).
     */
    suspend fun unlockWithRecoveryKey(recoveryKey: ByteArray): UnlockOutcome = withContext(Dispatchers.IO) {
        val dto = VaultConfig.read(vaultDir)
        try {
            vmk = VaultKeyManager.unlockWithRecoveryKey(recoveryKey, VaultConfig.recoveryWrappedVmkOf(dto))
            UnlockOutcome.Success
        } catch (e: VaultAuthenticationException) {
            UnlockOutcome.WrongCredential
        }
    }

    /** Zeroes the in-memory VMK and drops the reference. Idempotent. */
    fun lock() {
        vmk?.fill(0)
        vmk = null
    }

    private fun requireUnlocked(): ByteArray = vmk ?: throw VaultLockedException()

    suspend fun listEntries(): List<VaultManifestEntry> = withContext(Dispatchers.IO) {
        VaultManifest.read(vaultDir, requireUnlocked())
    }

    /**
     * Streams [input] into the vault as a new entry — never buffers the whole
     * file (PRD Phase 2 scope), so importing a multi-hundred-MB audiobook
     * doesn't require holding it all in RAM.
     *
     * Crash-safety, in this specific order and for a specific reason: encrypt
     * to a temp file, rename it into place, and only THEN update the
     * manifest. Found during review: an earlier draft updated the manifest
     * *before* the rename, which meant a crash between those two steps left
     * the manifest pointing at a file that didn't exist at its expected path
     * yet — `listEntries()` would show the title, `openReader()` would fail.
     * With rename-then-manifest, the worst case a crash can leave behind is
     * an orphaned content file with no manifest entry pointing at it —
     * wasted space, never a broken reference.
     *
     * @throws InsufficientStorageException not enough free space for [declaredSize]
     */
    suspend fun importFile(
        input: InputStream,
        declaredSize: Long,
        title: String,
        author: String?,
        format: String,
    ): VaultManifestEntry = withContext(Dispatchers.IO) {
        val vmkNow = requireUnlocked()

        // A generous margin above the declared size, not just >=: chunking overhead
        // (one AEAD tag per 32 KiB chunk) and the manifest rewrite both cost a
        // little more than the raw content size.
        val required = declaredSize + declaredSize / 32 + 16 * 1024
        val available = usableSpaceBytes()
        if (available < required) throw InsufficientStorageException(required, available)

        val fileId = newFileId()
        val tmp = File(vaultDir, "${fileId.toHexForFileName()}.tmp")
        val finalFile = contentFile(fileId)

        try {
            ChunkedVaultWriter.encrypt(vmkNow, fileId, declaredSize, input, tmp.outputStream())
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }

        try {
            check(tmp.renameTo(finalFile)) { "Failed to finalize imported file" }

            val entry = VaultManifestEntry(
                fileId = fileId,
                title = title,
                author = author,
                format = format,
                sizeBytes = declaredSize,
                addedAtEpochMillis = nowEpochMillis(),
            )
            val updatedEntries = VaultManifest.read(vaultDir, vmkNow) + entry
            VaultManifest.write(vaultDir, vmkNow, updatedEntries) // atomic — see VaultManifest.write
            entry
        } catch (e: Exception) {
            finalFile.delete()
            tmp.delete() // still present if renameTo itself is what failed
            throw e
        }
    }

    /** Opens a seekable decrypting reader for [fileId] — the primitive Phase 3's
     * content-delivery adapters (PDF proxy fd, Media3 DataSource, etc.) wrap. */
    fun openReader(fileId: ByteArray): VaultFileReader = VaultFileReader(contentFile(fileId), requireUnlocked(), fileId)

    /** On-disk path for a file's encrypted content — an opaque, hex-encoded id,
     * never the real filename (PRD §8.2 point 6). */
    fun contentFile(fileId: ByteArray): File = File(vaultDir, fileId.toHexForFileName())

    /** A fresh random file id, guaranteed not to collide with the reserved
     * [VaultManifest.MANIFEST_FILE_ID] (astronomically unlikely on its own,
     * but this is cheap insurance and a clear place to assert the invariant). */
    private fun newFileId(): ByteArray {
        while (true) {
            val id = ByteArray(VaultFormat.FILE_ID_SIZE_BYTES).also { random.nextBytes(it) }
            if (!id.contentEquals(VaultManifest.MANIFEST_FILE_ID)) return id
        }
    }
}

private fun ByteArray.toHexForFileName(): String = joinToString("") { "%02x".format(it) }
