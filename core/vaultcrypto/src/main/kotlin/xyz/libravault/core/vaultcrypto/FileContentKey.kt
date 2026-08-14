package xyz.libravault.core.vaultcrypto

// Fixed application-specific HKDF salt. Not a secret — HKDF salts don't need to
// be, their purpose is domain separation, not confidentiality.
private val FILE_CONTENT_HKDF_SALT = "vaultcrypto:hkdf-salt:v1".toByteArray(Charsets.US_ASCII)
private const val FILE_CONTENT_INFO_PREFIX = "vaultcrypto:file-content:v1"

/**
 * Derives a per-file content key from the Vault Master Key (PRD §8.2 point 3).
 *
 * Derived, never stored: compromising one file's key (e.g. via some future
 * side-channel specific to how a decrypted file is handled downstream) does not
 * help an attacker derive another file's key or the VMK itself, since HKDF is
 * one-way.
 */
internal fun deriveFileContentKey(vmk: ByteArray, fileId: ByteArray): ByteArray {
    require(fileId.size == VaultFormat.FILE_ID_SIZE_BYTES) {
        "fileId must be ${VaultFormat.FILE_ID_SIZE_BYTES} bytes"
    }
    val info = FILE_CONTENT_INFO_PREFIX.toByteArray(Charsets.US_ASCII) + fileId
    return Hkdf.deriveKey(FILE_CONTENT_HKDF_SALT, vmk, info, VaultFormat.VMK_SIZE_BYTES)
}
