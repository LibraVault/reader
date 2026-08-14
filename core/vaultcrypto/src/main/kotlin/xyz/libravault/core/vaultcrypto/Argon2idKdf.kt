package xyz.libravault.core.vaultcrypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Argon2id parameters for deriving the Key-Encryption-Key from a PIN/passphrase.
 *
 * Stored per-vault in the format header (§8.2b), NOT hardcoded globally — this
 * is what lets future releases change the default without breaking vaults
 * already created under an older one; only new vaults pick up a new default.
 *
 * [DEFAULT] is 19 MiB / t=2 / p=1 — PRD §8.4b. This is a **provisional**
 * default, extrapolated from four measured points on real hardware (Galaxy A12
 * budget device + Pixel 6), not itself directly measured. It targets ~750-850ms
 * on budget hardware, inside the ~500ms-1s goal, and happens to match OWASP's
 * own published minimum Argon2id profile. It is deliberately lighter than a
 * "maximum security" profile would otherwise call for, and that's a considered
 * trade, not an oversight: the Android Keystore hardware wrap layered on top of
 * this (added in core:vaultstore, Phase 2) already removes the offline brute
 * force attack that a heavy KDF exists to slow down. Argon2id's job here is
 * defence-in-depth if that wrap is ever bypassed, not the primary defense.
 *
 * TODO(Phase 1 follow-up, PRD §8.4b): confirm this default with one more
 * on-device benchmark (fold into the same Test Lab pass used for other
 * on-device verification) before shipping. If a native Argon2 implementation
 * (e.g. argon2kt, as Signal uses) is ever adopted instead of BouncyCastle, this
 * default should be revisited upward, since native implementations measured
 * several times faster in the sources this decision drew on.
 */
data class Argon2Params(
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
) {
    companion object {
        val DEFAULT = Argon2Params(memoryKiB = 19 * 1024, iterations = 2, parallelism = 1)
    }
}

/**
 * Derives a 256-bit key from a PIN/passphrase and salt using Argon2id.
 *
 * The caller is responsible for zeroing [pin] after use — this function does
 * not retain a copy, but Kotlin/JVM CharArrays are not automatically cleared.
 * Never pass the PIN as a [String]: Strings are immutable and may be interned
 * or retained by the JIT/GC in ways a CharArray is not.
 */
internal object Argon2idKdf {

    fun deriveKey(pin: CharArray, salt: ByteArray, params: Argon2Params, outputLengthBytes: Int = 32): ByteArray {
        require(salt.size == VaultFormat.ARGON2_SALT_SIZE_BYTES) {
            "salt must be ${VaultFormat.ARGON2_SALT_SIZE_BYTES} bytes"
        }
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(params.memoryKiB)
                .withIterations(params.iterations)
                .withParallelism(params.parallelism)
                .build(),
        )
        val out = ByteArray(outputLengthBytes)
        generator.generateBytes(pin, out)
        return out
    }
}
