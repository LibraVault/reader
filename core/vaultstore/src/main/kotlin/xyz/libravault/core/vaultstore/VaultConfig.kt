package xyz.libravault.core.vaultstore

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.libravault.core.vaultcrypto.Argon2Params
import xyz.libravault.core.vaultcrypto.VaultFormat
import xyz.libravault.core.vaultcrypto.VaultKeyMaterial
import xyz.libravault.core.vaultcrypto.WrappedKey
import java.io.File
import java.util.Base64

/**
 * Everything needed to unlock a vault, persisted as JSON in each vault's own
 * directory.
 *
 * **Deliberately does NOT store [VaultKeyMaterial.wrappedVmkByKek]'s
 * nonce/ciphertext separately in the clear.** That blob only exists as the
 * plaintext recovered by unwrapping [keystoreWrapNonceB64]/
 * [keystoreWrapCiphertextB64] (see [VaultStore]). Storing it a second time
 * outside the Keystore layer would let an attacker who copies this file skip
 * the hardware wrap entirely and go straight to offline Argon2id brute-force
 * against a 4-digit PIN — exactly the attack PRD §7.1's Keystore layer exists
 * to remove. This was corrected during design, before the first version of
 * this schema shipped anywhere — an earlier draft of this file stored both,
 * which would have silently defeated the entire point of the hardware wrap.
 *
 * [recoveryWrappedVmkNonceB64]/[recoveryWrappedVmkCiphertextB64] are fine to
 * store in the clear, by contrast: they're protected by the recovery key's
 * own 256 bits of entropy, not by being hidden, and they must be reachable
 * without the Keystore layer so they can rescue a vault whose Keystore key is
 * lost (implementation plan §A.5).
 *
 * [failedAttempts]/[lastAttemptEpochMillis] persist [UnlockAttemptThrottle]'s
 * state so a process restart doesn't reset it.
 */
@Serializable
data class VaultConfigDto(
    val formatVersion: Int = 1,
    val keystoreKeyAlias: String,
    val argon2SaltB64: String,
    val argon2MemoryKiB: Int,
    val argon2Iterations: Int,
    val argon2Parallelism: Int,
    val keystoreWrapNonceB64: String,
    val keystoreWrapCiphertextB64: String,
    val recoveryWrappedVmkNonceB64: String,
    val recoveryWrappedVmkCiphertextB64: String,
    val failedAttempts: Int = 0,
    val lastAttemptEpochMillis: Long = 0,
)

internal fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)
internal fun String.fromB64(): ByteArray = Base64.getDecoder().decode(this)

/** Concatenates a [WrappedKey]'s nonce and ciphertext into one blob, for
 * passing through [HardwareKeyWrap.wrap] — the fixed [VaultFormat.NONCE_SIZE_BYTES]
 * prefix makes it losslessly reversible via [bytesToWrappedKey]. */
internal fun WrappedKey.toBytes(): ByteArray = nonce + ciphertext

internal fun bytesToWrappedKey(bytes: ByteArray): WrappedKey = WrappedKey(
    nonce = bytes.copyOfRange(0, VaultFormat.NONCE_SIZE_BYTES),
    ciphertext = bytes.copyOfRange(VaultFormat.NONCE_SIZE_BYTES, bytes.size),
)

object VaultConfig {

    private const val FILE_NAME = "vault.json"
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    fun path(vaultDir: File): File = File(vaultDir, FILE_NAME)

    fun write(
        vaultDir: File,
        keystoreKeyAlias: String,
        argon2Salt: ByteArray,
        argon2Params: Argon2Params,
        keystoreWrap: WrappedBlob,
        wrappedVmkByRecovery: WrappedKey,
        failedAttempts: Int = 0,
        lastAttemptEpochMillis: Long = 0,
    ) {
        val dto = VaultConfigDto(
            keystoreKeyAlias = keystoreKeyAlias,
            argon2SaltB64 = argon2Salt.b64(),
            argon2MemoryKiB = argon2Params.memoryKiB,
            argon2Iterations = argon2Params.iterations,
            argon2Parallelism = argon2Params.parallelism,
            keystoreWrapNonceB64 = keystoreWrap.nonce.b64(),
            keystoreWrapCiphertextB64 = keystoreWrap.ciphertext.b64(),
            recoveryWrappedVmkNonceB64 = wrappedVmkByRecovery.nonce.b64(),
            recoveryWrappedVmkCiphertextB64 = wrappedVmkByRecovery.ciphertext.b64(),
            failedAttempts = failedAttempts,
            lastAttemptEpochMillis = lastAttemptEpochMillis,
        )
        writeAtomically(path(vaultDir), json.encodeToString(VaultConfigDto.serializer(), dto))
    }

    /** Rewrites just the throttle fields, without touching key material — used after every unlock attempt. */
    fun updateThrottleState(vaultDir: File, failedAttempts: Int, lastAttemptEpochMillis: Long) {
        val current = read(vaultDir)
        val updated = current.copy(failedAttempts = failedAttempts, lastAttemptEpochMillis = lastAttemptEpochMillis)
        writeAtomically(path(vaultDir), json.encodeToString(VaultConfigDto.serializer(), updated))
    }

    fun read(vaultDir: File): VaultConfigDto =
        json.decodeFromString(VaultConfigDto.serializer(), path(vaultDir).readText())

    fun exists(vaultDir: File): Boolean = path(vaultDir).exists()

    fun argon2ParamsOf(dto: VaultConfigDto): Argon2Params =
        Argon2Params(dto.argon2MemoryKiB, dto.argon2Iterations, dto.argon2Parallelism)

    fun keystoreWrapOf(dto: VaultConfigDto): WrappedBlob =
        WrappedBlob(dto.keystoreWrapNonceB64.fromB64(), dto.keystoreWrapCiphertextB64.fromB64())

    fun recoveryWrappedVmkOf(dto: VaultConfigDto): WrappedKey =
        WrappedKey(dto.recoveryWrappedVmkNonceB64.fromB64(), dto.recoveryWrappedVmkCiphertextB64.fromB64())

    /**
     * Write-to-temp-then-rename: `File.renameTo` on a POSIX filesystem is
     * atomic, so a crash mid-write can never leave a half-written config file —
     * readers always see either the old, complete version or the new,
     * complete version, never a truncated JSON blob.
     */
    private fun writeAtomically(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        check(tmp.renameTo(target)) { "Failed to atomically replace ${target.path}" }
    }
}
