import XCTest
import Security
@testable import LibraVault

/// Tests for `SecureEnclaveHardwareKeyWrap`/`SecureEnclaveHardwareKeyWrapFactory`
/// — issue #303.
///
/// The Simulator has no Secure Enclave hardware to emulate at all (unlike
/// Android's emulator, which can still produce a *software*-backed Keystore
/// key that `AndroidKeystoreHardwareKeyWrap.create()` explicitly refuses —
/// see `AndroidKeystoreHardwareKeyWrapTest`'s doc comment). So instead of
/// going through `SecureEnclaveHardwareKeyWrapFactory.createNew` for
/// everything, most tests here construct `SecureEnclaveHardwareKeyWrap`
/// directly against a plain, ephemeral, non-Secure-Enclave P-256 key
/// (`makePlainECKey()`) — the exact same bypass-production-creation
/// technique the Android androidTest uses (`generateRawKey`/`seedKeyAndWrap`),
/// for the identical reason: `wrap()`/`unwrap()` exercise the real ECIES
/// contract regardless of where the private key came from, only `createNew`
/// itself needs real hardware to verify.
///
/// What's covered here: the full ECIES wrap/unwrap contract (round-trip,
/// tamper detection, cross-key isolation), the Keychain lookup-by-tag
/// contract `forExisting`/`createNew` share, and `createNew`'s documented
/// failure in this environment. What's NOT covered, same gap Android has
/// (issue #253/#279): `createNew` actually succeeding with
/// `isHardwareBacked == true`, and passcode-change/biometric-re-enrollment
/// survival — both need a real device.
final class SecureEnclaveHardwareKeyWrapTests: XCTestCase {

    private var aliasesToClean: [String] = []

    override func tearDown() {
        aliasesToClean.forEach { deletePersistedKey(alias: $0) }
        aliasesToClean.removeAll()
        super.tearDown()
    }

    // MARK: - wrap/unwrap round trip

    func testRoundTripsEmptyPlaintext() throws {
        let wrap = SecureEnclaveHardwareKeyWrap(privateKey: try makePlainECKey(), isHardwareBacked: false)
        let blob = try wrap.wrap(Data())
        XCTAssertEqual(try wrap.unwrap(blob), Data())
    }

    func testRoundTripsOneBytePlaintext() throws {
        let wrap = SecureEnclaveHardwareKeyWrap(privateKey: try makePlainECKey(), isHardwareBacked: false)
        let plaintext = Data([0x42])
        let blob = try wrap.wrap(plaintext)
        XCTAssertEqual(try wrap.unwrap(blob), plaintext)
    }

    func testRoundTripsVmkSizedPlaintext() throws {
        // 32 bytes — the real VMK size this wraps in production (it's
        // actually the already-KEK-wrapped VMK blob that gets wrapped again
        // here, per `VaultKeyMaterial`'s doc comment, but the crypto itself
        // is size-agnostic).
        let wrap = SecureEnclaveHardwareKeyWrap(privateKey: try makePlainECKey(), isHardwareBacked: false)
        let plaintext = Data((0..<32).map { UInt8($0) })
        let blob = try wrap.wrap(plaintext)
        XCTAssertEqual(try wrap.unwrap(blob), plaintext)
    }

    // MARK: - ciphertext is not reused (ECIES draws a fresh ephemeral key per call)

    func testWrapCallsOnIdenticalPlaintextProduceDifferentCiphertext() throws {
        let wrap = SecureEnclaveHardwareKeyWrap(privateKey: try makePlainECKey(), isHardwareBacked: false)
        let plaintext = Data(repeating: 0x7, count: 32)

        let first = try wrap.wrap(plaintext)
        let second = try wrap.wrap(plaintext)

        // Same nonce/ciphertext-reuse concern as Android's equivalent test
        // (`nonceIsNotReusedAcrossWrapCallsOnIdenticalPlaintext`) — ECIES's
        // ephemeral key generation is what guarantees this here, rather
        // than an explicit nonce argument to inspect.
        XCTAssertNotEqual(first, second)
    }

    // MARK: - tamper detection

    func testTamperedCiphertextThrowsAuthenticationFailed() throws {
        let wrap = SecureEnclaveHardwareKeyWrap(privateKey: try makePlainECKey(), isHardwareBacked: false)
        let blob = try wrap.wrap(Data(repeating: 1, count: 32))
        var tamperedBytes = blob.ciphertext
        tamperedBytes[tamperedBytes.startIndex] ^= 0x01
        let tampered = WrappedBlob(ciphertext: tamperedBytes)

        XCTAssertThrowsError(try wrap.unwrap(tampered)) { error in
            XCTAssertEqual(error as? VaultCryptoError, .authenticationFailed)
        }
    }

    func testTruncatedCiphertextThrowsAuthenticationFailed() throws {
        let wrap = SecureEnclaveHardwareKeyWrap(privateKey: try makePlainECKey(), isHardwareBacked: false)
        let blob = try wrap.wrap(Data(repeating: 1, count: 32))
        let truncated = WrappedBlob(ciphertext: blob.ciphertext.dropFirst(4))

        XCTAssertThrowsError(try wrap.unwrap(truncated)) { error in
            XCTAssertEqual(error as? VaultCryptoError, .authenticationFailed)
        }
    }

    // MARK: - per-vault isolation

    func testBlobWrappedUnderOneKeyDoesNotUnwrapUnderAnotherKey() throws {
        let wrapA = SecureEnclaveHardwareKeyWrap(privateKey: try makePlainECKey(), isHardwareBacked: false)
        let wrapB = SecureEnclaveHardwareKeyWrap(privateKey: try makePlainECKey(), isHardwareBacked: false)
        let blob = try wrapA.wrap(Data(repeating: 9, count: 32))

        // The property that stops one compromised vault key from opening
        // another.
        XCTAssertThrowsError(try wrapB.unwrap(blob)) { error in
            XCTAssertEqual(error as? VaultCryptoError, .authenticationFailed)
        }
    }

    // MARK: - factory: forExisting

    func testForExistingOnMissingAliasThrowsKeyLost() {
        let factory = SecureEnclaveHardwareKeyWrapFactory()
        let missingAlias = uniqueAlias() // deliberately never seeded

        XCTAssertThrowsError(try factory.forExisting(keyAlias: missingAlias)) { error in
            XCTAssertEqual(error as? HardwareKeyWrapError, .keyLost(keyAlias: missingAlias))
        }
    }

    func testForExistingFindsAManuallySeededKeyAndRoundTrips() throws {
        // Bypasses `createNew` deliberately — see class doc comment. This
        // exercises the Keychain lookup-by-tag contract `forExisting` and
        // `createNew` share, without needing real Secure Enclave hardware.
        let alias = uniqueAlias()
        aliasesToClean.append(alias)
        _ = try seedPersistedKey(alias: alias)

        let factory = SecureEnclaveHardwareKeyWrapFactory()
        let wrap = try factory.forExisting(keyAlias: alias)

        let plaintext = Data(repeating: 5, count: 32)
        let blob = try wrap.wrap(plaintext)
        XCTAssertEqual(try wrap.unwrap(blob), plaintext)
    }

    func testForExistingReturnsAFreshInstanceEachCallForTheSameKey() throws {
        let alias = uniqueAlias()
        aliasesToClean.append(alias)
        _ = try seedPersistedKey(alias: alias)

        let factory = SecureEnclaveHardwareKeyWrapFactory()
        let firstInstance = try factory.forExisting(keyAlias: alias)
        let plaintext = Data(repeating: 5, count: 32)
        let blob = try firstInstance.wrap(plaintext)

        // A fresh instance, not reused — guards against the key living only
        // in an in-memory cache on the first instance rather than genuinely
        // round-tripping through the Keychain by tag.
        let secondInstance = try factory.forExisting(keyAlias: alias)
        XCTAssertEqual(try secondInstance.unwrap(blob), plaintext)
    }

    // MARK: - factory: createNew (Simulator has no Secure Enclave to emulate)

    func testCreateNewThrowsSecureEnclaveUnavailableInSimulator() throws {
        // Documents this CI environment's actual behavior, same as
        // `AndroidKeystoreHardwareKeyWrapTest.createThrowsAndDeletesTheSoftwareBackedKeyItRejects`
        // documents its own. `createNew` actually succeeding, with
        // `isHardwareBacked == true`, needs real hardware and is tracked as
        // a follow-up (mirrors Android issue #253/#279's precedent), not
        // attempted here.
        #if targetEnvironment(simulator)
        let factory = SecureEnclaveHardwareKeyWrapFactory()
        let alias = uniqueAlias()

        XCTAssertThrowsError(try factory.createNew(keyAlias: alias)) { error in
            guard case .secureEnclaveUnavailable = error as? HardwareKeyWrapError else {
                XCTFail("expected .secureEnclaveUnavailable, got \(error)")
                return
            }
        }
        #else
        throw XCTSkip("Only meaningful on the Simulator, where the Secure Enclave is known to be unavailable")
        #endif
    }

    // MARK: - helpers

    private func uniqueAlias() -> String { "test-\(UUID().uuidString)" }

    /// A plain, ephemeral, non-persisted, non-Secure-Enclave P-256 key —
    /// works everywhere, including the Simulator. See class doc comment.
    private func makePlainECKey() throws -> SecKey {
        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            throw error!.takeRetainedValue() as Error
        }
        return key
    }

    /// Duplicates `SecureEnclaveHardwareKeyWrapFactory`'s private tag
    /// format deliberately, rather than exposing it for reuse — see that
    /// type's doc comment.
    private func tag(for alias: String) -> Data {
        Data("xyz.libravault.vaultstore.se.\(alias)".utf8)
    }

    /// A plain (non-Secure-Enclave) P-256 key persisted to the Keychain
    /// under the same tag shape `SecureEnclaveHardwareKeyWrapFactory` uses —
    /// lets `forExisting`'s lookup-by-tag logic be tested without real SE
    /// hardware.
    private func seedPersistedKey(alias: String) throws -> SecKey {
        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: tag(for: alias),
            ],
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            throw error!.takeRetainedValue() as Error
        }
        return key
    }

    private func deletePersistedKey(alias: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: tag(for: alias),
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
