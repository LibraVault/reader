import XCTest
@testable import LibraVault

/// Mirrors Android's `VaultKeyManagerTest.kt`.
final class VaultKeyManagerTests: XCTestCase {

    // Small params - correctness tests, not latency benchmarks.
    private let fastParams = Argon2Params(memoryKiB: 8 * 1024, iterations: 1, parallelism: 1)

    func testUnlockingWithTheCorrectPinReturnsTheSameVmkThatWasCreated() throws {
        let created = try VaultKeyManager.create(pin: Array("1234".utf8), argon2Params: fastParams)
        let unlocked = try VaultKeyManager.unlockWithPin(pin: Array("1234".utf8), material: created.material)
        XCTAssertEqual(created.vmk, unlocked)
    }

    func testUnlockingWithTheWrongPinFails() throws {
        let created = try VaultKeyManager.create(pin: Array("1234".utf8), argon2Params: fastParams)
        XCTAssertThrowsError(try VaultKeyManager.unlockWithPin(pin: Array("9999".utf8), material: created.material)) { error in
            XCTAssertEqual(.authenticationFailed, error as? VaultCryptoError)
        }
    }

    func testUnlockingWithTheRecoveryKeyReturnsTheSameVmkIndependentOfThePin() throws {
        let created = try VaultKeyManager.create(pin: Array("1234".utf8), argon2Params: fastParams)
        let unlocked = try VaultKeyManager.unlockWithRecoveryKey(recoveryKey: created.recoveryKey, material: created.material)
        XCTAssertEqual(created.vmk, unlocked)
    }

    func testRecoveryKeyPathWorksEvenIfTheKekWrappedBlobIsCorrupted() throws {
        // Simulates the Secure Enclave-wrapped layer (added on top of
        // wrappedVmkByKek in Phase 2, outside this module) being lost or
        // corrupted. The recovery path must still work because it never
        // depends on wrappedVmkByKek at all.
        let created = try VaultKeyManager.create(pin: Array("1234".utf8), argon2Params: fastParams)
        var corruptedMaterial = created.material
        corruptedMaterial.wrappedVmkByKek = WrappedKey(
            nonce: created.material.wrappedVmkByKek.nonce,
            ciphertext: Data(count: created.material.wrappedVmkByKek.ciphertext.count) // garbage (all zero)
        )

        let unlocked = try VaultKeyManager.unlockWithRecoveryKey(recoveryKey: created.recoveryKey, material: corruptedMaterial)
        XCTAssertEqual(created.vmk, unlocked)
    }

    func testWrongRecoveryKeyFails() throws {
        let created = try VaultKeyManager.create(pin: Array("1234".utf8), argon2Params: fastParams)
        let wrongRecoveryKey = Data(count: VaultFormat.recoveryKeySizeBytes)
        XCTAssertThrowsError(try VaultKeyManager.unlockWithRecoveryKey(recoveryKey: wrongRecoveryKey, material: created.material)) { error in
            XCTAssertEqual(.authenticationFailed, error as? VaultCryptoError)
        }
    }

    func testAKekWrappedBlobCannotBeUnwrappedAsIfItWereTheRecoveryWrappedBlob() throws {
        // Proves the two AAD contexts (kek-wrap vs recovery-wrap) actually
        // separate the two wrappings, rather than one accidentally working for both.
        let created = try VaultKeyManager.create(pin: Array("1234".utf8), argon2Params: fastParams)
        XCTAssertThrowsError(
            try KeyWrap.unwrap(
                wrappingKey: created.recoveryKey,
                wrapped: created.material.wrappedVmkByKek, // wrong blob for this AAD context
                aad: Data("vaultcrypto:vmk-wrap:recovery:v1".utf8)
            )
        ) { error in
            XCTAssertEqual(.authenticationFailed, error as? VaultCryptoError)
        }
    }

    func testChangePinRewrapsTheVmkUnderANewPinWithoutTouchingTheRecoveryWrap() throws {
        let created = try VaultKeyManager.create(pin: Array("1234".utf8), argon2Params: fastParams)
        let newMaterial = try VaultKeyManager.changePin(
            oldPin: Array("1234".utf8),
            newPin: Array("5678".utf8),
            material: created.material,
            newArgon2Params: fastParams
        )

        // New PIN works, old PIN doesn't.
        XCTAssertEqual(created.vmk, try VaultKeyManager.unlockWithPin(pin: Array("5678".utf8), material: newMaterial))
        XCTAssertThrowsError(try VaultKeyManager.unlockWithPin(pin: Array("1234".utf8), material: newMaterial)) { error in
            XCTAssertEqual(.authenticationFailed, error as? VaultCryptoError)
        }

        // Recovery wrap is untouched - same VMK, no re-generation needed.
        XCTAssertEqual(created.vmk, try VaultKeyManager.unlockWithRecoveryKey(recoveryKey: created.recoveryKey, material: newMaterial))
        XCTAssertEqual(created.material.wrappedVmkByRecovery, newMaterial.wrappedVmkByRecovery)
    }

    func testTwoVaultsCreatedIndependentlyNeverShareAVmkOrRecoveryKey() throws {
        let a = try VaultKeyManager.create(pin: Array("1234".utf8), argon2Params: fastParams)
        let b = try VaultKeyManager.create(pin: Array("1234".utf8), argon2Params: fastParams) // same PIN on purpose
        XCTAssertNotEqual(a.vmk, b.vmk)
        XCTAssertNotEqual(a.recoveryKey, b.recoveryKey)
    }
}
