import Foundation

/// On-disk wire format for `vault.json`. Field names deliberately mirror
/// Android's `VaultConfigDto` (`core/vaultstore`). Unlike Android,
/// failed-PIN throttle state (`failedAttempts`/`lastAttemptEpochMillis`)
/// lives in its own file (`UnlockAttemptThrottleStore`, #301) rather than
/// folded into this one — no behavior difference, just a file split #301
/// already established before this issue started. No legacy-format concern
/// here (unlike `VaultManifest`'s custom decoder): this is a brand-new type
/// with no pre-existing vault.json files to stay compatible with, so a
/// plain synthesized `Codable` is enough.
private struct VaultConfigDto: Codable {
    let formatVersion: Int
    let keystoreKeyAlias: String
    let argon2SaltB64: String
    let argon2MemoryKiB: Int
    let argon2Iterations: Int
    let argon2Parallelism: Int
    let keystoreWrapCiphertextB64: String
    let recoveryWrappedVmkNonceB64: String
    let recoveryWrappedVmkCiphertextB64: String
}

/// Decoded, ready-to-use form of a vault's persisted config — what
/// `VaultConfig.read` returns. Base64/DTO plumbing stays internal to this
/// file; callers get already-typed `Data`/`WrappedBlob`/`WrappedKey`/
/// `Argon2Params` values directly, unlike Android's `VaultConfigDto` plus
/// separate `argon2ParamsOf`/`keystoreWrapOf`/`recoveryWrappedVmkOf`
/// helper-function trio.
struct VaultConfigData {
    let keystoreKeyAlias: String
    let argon2Salt: Data
    let argon2Params: Argon2Params
    let keystoreWrap: WrappedBlob
    let wrappedVmkByRecovery: WrappedKey
}

extension WrappedKey {
    /// Concatenates `nonce` + `ciphertext` into one blob, for passing
    /// through `HardwareKeyWrap.wrap` — the fixed `VaultFormat.nonceSizeBytes`
    /// prefix makes it losslessly reversible via `init(serialized:)`. Mirrors
    /// Android's `WrappedKey.toBytes()`/`bytesToWrappedKey()` (`VaultConfig.kt`).
    var serialized: Data { nonce + ciphertext }

    init(serialized data: Data) {
        self.init(nonce: data.prefix(VaultFormat.nonceSizeBytes), ciphertext: data.dropFirst(VaultFormat.nonceSizeBytes))
    }
}

/// Everything needed to unlock a vault, persisted as JSON in the vault's own
/// directory (`vault.json`). Swift port of Android's `VaultConfig` object.
///
/// **Deliberately does NOT store the KEK-wrapped VMK's nonce/ciphertext a
/// second time in the clear.** That blob only exists as the plaintext
/// recovered by unwrapping `keystoreWrap` (see `VaultStore.unlockWithPin`).
/// Storing it a second time outside the Secure Enclave wrap would let an
/// attacker who copies this file skip the hardware wrap entirely and go
/// straight to offline Argon2id brute-force against a 4-digit PIN — exactly
/// what the Secure Enclave layer (#303) exists to prevent. Same reasoning
/// Android's `VaultConfigDto` doc comment documents for its own schema.
///
/// `recoveryWrappedVmkNonceB64`/`recoveryWrappedVmkCiphertextB64` are fine to
/// store in the clear, by contrast: protected by the recovery key's own 256
/// bits of entropy, not by being hidden, and must be reachable without the
/// Secure Enclave layer so they can rescue a vault whose Secure Enclave key
/// is lost.
enum VaultConfig {

    private static let fileName = "vault.json"

    static func path(vaultDir: URL) -> URL {
        vaultDir.appendingPathComponent(fileName)
    }

    static func exists(vaultDir: URL) -> Bool {
        FileManager.default.fileExists(atPath: path(vaultDir: vaultDir).path)
    }

    static func write(
        vaultDir: URL,
        keystoreKeyAlias: String,
        argon2Salt: Data,
        argon2Params: Argon2Params,
        keystoreWrap: WrappedBlob,
        wrappedVmkByRecovery: WrappedKey
    ) throws {
        let dto = VaultConfigDto(
            formatVersion: 1,
            keystoreKeyAlias: keystoreKeyAlias,
            argon2SaltB64: argon2Salt.base64EncodedString(),
            argon2MemoryKiB: argon2Params.memoryKiB,
            argon2Iterations: argon2Params.iterations,
            argon2Parallelism: argon2Params.parallelism,
            keystoreWrapCiphertextB64: keystoreWrap.ciphertext.base64EncodedString(),
            recoveryWrappedVmkNonceB64: wrappedVmkByRecovery.nonce.base64EncodedString(),
            recoveryWrappedVmkCiphertextB64: wrappedVmkByRecovery.ciphertext.base64EncodedString()
        )
        try FileManager.default.createDirectory(at: vaultDir, withIntermediateDirectories: true)
        let data = try JSONEncoder().encode(dto)
        // .atomic: writes to an auxiliary file then renames into place, same
        // write-to-temp-then-rename guarantee as VaultRegistry.writeAll (#301)
        // and Android's own File.renameTo-based writeAtomically.
        try data.write(to: path(vaultDir: vaultDir), options: .atomic)
    }

    /// - Throws: a decoding/`Data(contentsOf:)` error if `vault.json` is
    ///   missing or malformed, or `VaultCryptoError.malformedHeader` if a
    ///   base64 field is corrupt.
    static func read(vaultDir: URL) throws -> VaultConfigData {
        let data = try Data(contentsOf: path(vaultDir: vaultDir))
        let dto = try JSONDecoder().decode(VaultConfigDto.self, from: data)

        guard let salt = Data(base64Encoded: dto.argon2SaltB64),
              let keystoreCiphertext = Data(base64Encoded: dto.keystoreWrapCiphertextB64),
              let recoveryNonce = Data(base64Encoded: dto.recoveryWrappedVmkNonceB64),
              let recoveryCiphertext = Data(base64Encoded: dto.recoveryWrappedVmkCiphertextB64)
        else {
            throw VaultCryptoError.malformedHeader("vault.json contains invalid base64")
        }

        return VaultConfigData(
            keystoreKeyAlias: dto.keystoreKeyAlias,
            argon2Salt: salt,
            argon2Params: Argon2Params(
                memoryKiB: dto.argon2MemoryKiB,
                iterations: dto.argon2Iterations,
                parallelism: dto.argon2Parallelism
            ),
            keystoreWrap: WrappedBlob(ciphertext: keystoreCiphertext),
            wrappedVmkByRecovery: WrappedKey(nonce: recoveryNonce, ciphertext: recoveryCiphertext)
        )
    }
}
