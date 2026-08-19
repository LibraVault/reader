import XCTest
@testable import LibraVault

final class VaultConfigTests: XCTestCase {

    private func newVaultDir() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("vaultconfig-test-\(UUID().uuidString)")
    }

    func testWriteThenReadRoundTrips() throws {
        let vaultDir = newVaultDir()
        let keystoreWrap = WrappedBlob(ciphertext: VaultCryptoTestSupport.randomData(48))
        let recoveryWrap = WrappedKey(nonce: VaultCryptoTestSupport.randomData(12), ciphertext: VaultCryptoTestSupport.randomData(48))
        let salt = VaultCryptoTestSupport.randomData(16)
        let params = Argon2Params(memoryKiB: 8 * 1024, iterations: 2, parallelism: 1)

        try VaultConfig.write(
            vaultDir: vaultDir,
            keystoreKeyAlias: "alias-1",
            argon2Salt: salt,
            argon2Params: params,
            keystoreWrap: keystoreWrap,
            wrappedVmkByRecovery: recoveryWrap
        )

        let readBack = try VaultConfig.read(vaultDir: vaultDir)
        XCTAssertEqual(readBack.keystoreKeyAlias, "alias-1")
        XCTAssertEqual(readBack.argon2Salt, salt)
        XCTAssertEqual(readBack.argon2Params, params)
        XCTAssertEqual(readBack.keystoreWrap, keystoreWrap)
        XCTAssertEqual(readBack.wrappedVmkByRecovery, recoveryWrap)
    }

    func testExistsReflectsWhetherVaultJsonHasBeenWritten() throws {
        let vaultDir = newVaultDir()
        XCTAssertFalse(VaultConfig.exists(vaultDir: vaultDir))

        try VaultConfig.write(
            vaultDir: vaultDir,
            keystoreKeyAlias: "alias",
            argon2Salt: VaultCryptoTestSupport.randomData(16),
            argon2Params: .defaultParams,
            keystoreWrap: WrappedBlob(ciphertext: VaultCryptoTestSupport.randomData(48)),
            wrappedVmkByRecovery: WrappedKey(nonce: VaultCryptoTestSupport.randomData(12), ciphertext: VaultCryptoTestSupport.randomData(48))
        )

        XCTAssertTrue(VaultConfig.exists(vaultDir: vaultDir))
    }

    /// `WrappedKey.serialized`/`init(serialized:)` is the losslessly
    /// reversible nonce+ciphertext concatenation `VaultStore.create`/
    /// `unlockWithPin` pass through the hardware-wrap layer — this is the
    /// property that makes that round-trip safe.
    func testWrappedKeySerializedRoundTrips() {
        let key = WrappedKey(nonce: VaultCryptoTestSupport.randomData(12), ciphertext: VaultCryptoTestSupport.randomData(48))
        XCTAssertEqual(WrappedKey(serialized: key.serialized), key)
    }

    /// `VaultStore.unlockWithPin`/`unlockWithRecoveryKey` both start with
    /// `VaultConfig.read` — a missing `vault.json` (e.g. a half-created
    /// vault directory, or a caller that got the path wrong) must surface as
    /// a thrown error, never as empty/default config data silently accepted.
    func testReadThrowsWhenVaultJsonIsMissing() {
        let vaultDir = newVaultDir() // never written to
        XCTAssertThrowsError(try VaultConfig.read(vaultDir: vaultDir))
    }

    /// Corrupt base64 in any field must throw `.malformedHeader`, not crash
    /// or silently substitute empty data — same contract `VaultConfig.read`'s
    /// own doc comment documents.
    func testReadThrowsMalformedHeaderOnCorruptBase64() throws {
        let vaultDir = newVaultDir()
        try VaultConfig.write(
            vaultDir: vaultDir,
            keystoreKeyAlias: "alias",
            argon2Salt: VaultCryptoTestSupport.randomData(16),
            argon2Params: .defaultParams,
            keystoreWrap: WrappedBlob(ciphertext: VaultCryptoTestSupport.randomData(48)),
            wrappedVmkByRecovery: WrappedKey(nonce: VaultCryptoTestSupport.randomData(12), ciphertext: VaultCryptoTestSupport.randomData(48))
        )

        // Corrupt the on-disk JSON's argon2SaltB64 field in place — "not
        // valid base64" rather than "missing" or "wrong type", to target the
        // Data(base64Encoded:) guard specifically.
        var json = try String(contentsOf: VaultConfig.path(vaultDir: vaultDir), encoding: .utf8)
        json = json.replacingOccurrences(of: "\"argon2SaltB64\":\"", with: "\"argon2SaltB64\":\"not-valid-base64!!!")
        try json.write(to: VaultConfig.path(vaultDir: vaultDir), atomically: true, encoding: .utf8)

        XCTAssertThrowsError(try VaultConfig.read(vaultDir: vaultDir)) { error in
            guard case .malformedHeader = error as? VaultCryptoError else {
                XCTFail("expected .malformedHeader, got \(error)")
                return
            }
        }
    }
}
