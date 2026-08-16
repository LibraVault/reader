import Foundation

// AAD context strings distinguish the two wrappings of the VMK so one can never
// be substituted for the other even though both wrap the same key bytes.
private let kekWrapAad = Data("vaultcrypto:vmk-wrap:kek:v1".utf8)
private let recoveryWrapAad = Data("vaultcrypto:vmk-wrap:recovery:v1".utf8)

/// Everything needed to later unlock a vault, EXCEPT the secrets themselves
/// (PIN, recovery key, VMK) - this is what gets persisted in the vault header.
///
/// Per PRD §8.2 point 2b, `wrappedVmkByKek` is wrapped *again* by a
/// non-exportable Secure Enclave key (see the handoff spec's
/// `HardwareKeyWrap` design, Phase 2) - that additional layer is deliberately
/// outside this module, which has no platform-specific dependency at all.
/// `wrappedVmkByRecovery` deliberately bypasses that hardware layer entirely,
/// so it can still rescue the vault if the Secure Enclave key is ever lost -
/// that's why it's a completely independent wrapping here, not derived from
/// or dependent on the KEK path in any way.
struct VaultKeyMaterial: Equatable {
    var argon2Salt: Data
    var argon2Params: Argon2Params
    var wrappedVmkByKek: WrappedKey
    var wrappedVmkByRecovery: WrappedKey
}

/// Result of creating a brand-new vault: the persistable `material`, the raw
/// `vmk` (ready to use immediately, e.g. to encrypt the first imported file),
/// and the `recoveryKey` that MUST be shown to the user exactly once - this
/// module does not retain or persist it anywhere.
struct NewVault {
    let material: VaultKeyMaterial
    let vmk: Data
    let recoveryKey: Data
}

/// Creates and unlocks the key hierarchy described in PRD §8.2:
///
///   PIN --Argon2id--> KEK --+
///                           +-> wrapped VMK (this is then wrapped AGAIN by
///   (recovery key) ---------+    a Secure Enclave key, in Phase 2)
///
/// Callers must additionally apply/remove the Secure Enclave wrap around
/// `VaultKeyMaterial.wrappedVmkByKek` - that's out of scope for this
/// pure-Swift, CryptoKit-only module by design (same Phase 1 vs Phase 2 split
/// as the Android side's Android Keystore layer).
enum VaultKeyManager {

    static func create(pin: [UInt8], argon2Params: Argon2Params = .defaultParams) throws -> NewVault {
        let vmk = try SecureRandom.bytes(count: VaultFormat.vmkSizeBytes)
        let recoveryKey = try SecureRandom.bytes(count: VaultFormat.recoveryKeySizeBytes)
        let salt = try SecureRandom.bytes(count: VaultFormat.argon2SaltSizeBytes)

        var kek = try Argon2idKdf.deriveKey(pin: pin, salt: salt, params: argon2Params)
        defer { kek.secureZero() }
        let wrappedByKek = try KeyWrap.wrap(wrappingKey: kek, plaintextKey: vmk, aad: kekWrapAad)

        let wrappedByRecovery = try KeyWrap.wrap(wrappingKey: recoveryKey, plaintextKey: vmk, aad: recoveryWrapAad)

        return NewVault(
            material: VaultKeyMaterial(
                argon2Salt: salt,
                argon2Params: argon2Params,
                wrappedVmkByKek: wrappedByKek,
                wrappedVmkByRecovery: wrappedByRecovery
            ),
            vmk: vmk,
            recoveryKey: recoveryKey
        )
    }

    /// - Throws: `VaultCryptoError.authenticationFailed` on a wrong PIN.
    static func unlockWithPin(pin: [UInt8], material: VaultKeyMaterial) throws -> Data {
        try unlockWithPin(
            pin: pin,
            argon2Salt: material.argon2Salt,
            argon2Params: material.argon2Params,
            wrappedVmkByKek: material.wrappedVmkByKek
        )
    }

    /// Same as the `VaultKeyMaterial` overload above, but takes just the three
    /// fields this path actually reads - mirrors the equivalent Android
    /// overload, added because the Secure Enclave integration (Phase 2)
    /// recovers `wrappedVmkByKek` by unwrapping the hardware layer first, and
    /// has nothing to put in the unused `wrappedVmkByRecovery` field except a
    /// placeholder.
    static func unlockWithPin(
        pin: [UInt8],
        argon2Salt: Data,
        argon2Params: Argon2Params,
        wrappedVmkByKek: WrappedKey
    ) throws -> Data {
        var kek = try Argon2idKdf.deriveKey(pin: pin, salt: argon2Salt, params: argon2Params)
        defer { kek.secureZero() }
        return try KeyWrap.unwrap(wrappingKey: kek, wrapped: wrappedVmkByKek, aad: kekWrapAad)
    }

    /// - Throws: `VaultCryptoError.authenticationFailed` on a wrong recovery key.
    ///
    /// Deliberately independent of `unlockWithPin` and of any Secure Enclave
    /// state - this is the path that rescues a vault whose hardware-wrapped
    /// key was lost. It must keep working even if the PIN path is completely broken.
    static func unlockWithRecoveryKey(recoveryKey: Data, material: VaultKeyMaterial) throws -> Data {
        try unlockWithRecoveryKey(recoveryKey: recoveryKey, wrappedVmkByRecovery: material.wrappedVmkByRecovery)
    }

    /// Same as the `VaultKeyMaterial` overload above, but takes just the
    /// recovery-wrapped blob directly - mirrors the equivalent Android
    /// overload, added so a caller with only the recovery-wrapped blob on
    /// hand doesn't need to construct a `VaultKeyMaterial` with placeholder
    /// values for three unused fields just to satisfy the type.
    static func unlockWithRecoveryKey(recoveryKey: Data, wrappedVmkByRecovery: WrappedKey) throws -> Data {
        try KeyWrap.unwrap(wrappingKey: recoveryKey, wrapped: wrappedVmkByRecovery, aad: recoveryWrapAad)
    }

    /// Re-wraps the VMK under a new PIN. Only the small
    /// `VaultKeyMaterial.wrappedVmkByKek` blob changes - no file content is
    /// touched, which is the entire point of the VMK indirection (PRD §8.2
    /// point 2). `wrappedVmkByRecovery` is untouched: changing the PIN does
    /// not require (and should not trigger) generating a new recovery key.
    static func changePin(
        oldPin: [UInt8],
        newPin: [UInt8],
        material: VaultKeyMaterial,
        newArgon2Params: Argon2Params? = nil
    ) throws -> VaultKeyMaterial {
        let params = newArgon2Params ?? material.argon2Params
        var vmk = try unlockWithPin(pin: oldPin, material: material)
        defer { vmk.secureZero() }

        let newSalt = try SecureRandom.bytes(count: VaultFormat.argon2SaltSizeBytes)
        var newKek = try Argon2idKdf.deriveKey(pin: newPin, salt: newSalt, params: params)
        defer { newKek.secureZero() }
        let newWrappedByKek = try KeyWrap.wrap(wrappingKey: newKek, plaintextKey: vmk, aad: kekWrapAad)

        var result = material
        result.argon2Salt = newSalt
        result.argon2Params = params
        result.wrappedVmkByKek = newWrappedByKek
        return result
    }
}
