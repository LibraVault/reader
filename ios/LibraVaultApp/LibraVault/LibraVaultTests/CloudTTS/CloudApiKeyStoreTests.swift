import XCTest
import Security
@testable import LibraVault

/// Tests for `KeychainCloudApiKeyStore` against the REAL Keychain — Simulator-safe,
/// unlike Secure Enclave key persistence (see `SecureEnclaveHardwareKeyWrapTests`'s doc
/// comment): `kSecClassGenericPassword` items don't need real hardware, only Keychain
/// availability. Still guarded the same way that file is, though: this repo's
/// `ios-app-build.yml` CI job runs `xcodebuild test` with `CODE_SIGNING_ALLOWED=NO`,
/// leaving the test bundle with zero entitlements — confirmed (2026-08-19, see that
/// file) to break `kSecClassKey` persistence with `errSecMissingEntitlement`, and
/// generic-password persistence is not guaranteed to be exempt from the same
/// restriction. `skipIfKeychainPersistenceUnavailable` below mirrors that file's own
/// skip helper so this suite degrades the same way instead of failing CI outright if
/// that turns out to be true here too.
final class CloudApiKeyStoreTests: XCTestCase {

    private var providersToClean: [CloudProviderId] = []

    override func tearDown() {
        let store = KeychainCloudApiKeyStore()
        providersToClean.forEach { store.clear(provider: $0) }
        providersToClean.removeAll()
        super.tearDown()
    }

    private func skipIfKeychainPersistenceUnavailable(_ error: Error) throws {
        guard case CloudApiKeyStoreError.keychainError(let status) = error, status == errSecMissingEntitlement else {
            return
        }
        throw XCTSkip("Keychain generic-password persistence unavailable (errSecMissingEntitlement) — this CI job runs with CODE_SIGNING_ALLOWED=NO; needs a signed build or real device")
    }

    func testLoadReturnsNilWhenNothingSaved() {
        let store = KeychainCloudApiKeyStore()
        XCTAssertNil(store.load(provider: .elevenLabs))
    }

    func testSaveThenLoadRoundTripsSingleFieldCredentials() throws {
        let store = KeychainCloudApiKeyStore()
        providersToClean.append(.openAI)
        do {
            try store.save(provider: .openAI, credentials: [.apiKey: "sk-test-value"])
        } catch {
            try skipIfKeychainPersistenceUnavailable(error)
            throw error
        }
        XCTAssertEqual(store.load(provider: .openAI), [.apiKey: "sk-test-value"])
    }

    func testSaveThenLoadRoundTripsMultiFieldCredentials() throws {
        let store = KeychainCloudApiKeyStore()
        providersToClean.append(.amazonPolly)
        let credentials: [CloudCredentialField: String] = [
            .accessKeyID: "AKIAEXAMPLE",
            .secretAccessKey: "shh-its-a-secret",
            .region: "us-east-1",
        ]
        do {
            try store.save(provider: .amazonPolly, credentials: credentials)
        } catch {
            try skipIfKeychainPersistenceUnavailable(error)
            throw error
        }
        XCTAssertEqual(store.load(provider: .amazonPolly), credentials)
    }

    func testSaveRejectsCredentialsMissingARequiredField() {
        let store = KeychainCloudApiKeyStore()
        XCTAssertThrowsError(try store.save(provider: .azureSpeech, credentials: [.apiKey: "key-only-no-region"])) { error in
            XCTAssertEqual(error as? CloudApiKeyStoreError, .fieldMismatch(provider: .azureSpeech))
        }
    }

    func testSaveRejectsCredentialsWithAnExtraField() {
        let store = KeychainCloudApiKeyStore()
        let extra: [CloudCredentialField: String] = [.apiKey: "key", .region: "unexpected-for-this-provider"]
        XCTAssertThrowsError(try store.save(provider: .openAI, credentials: extra)) { error in
            XCTAssertEqual(error as? CloudApiKeyStoreError, .fieldMismatch(provider: .openAI))
        }
    }

    func testSaveOverwritesAnyExistingCredentialsForTheSameProvider() throws {
        let store = KeychainCloudApiKeyStore()
        providersToClean.append(.googleCloudTTS)
        do {
            try store.save(provider: .googleCloudTTS, credentials: [.apiKey: "first-key"])
            try store.save(provider: .googleCloudTTS, credentials: [.apiKey: "second-key"])
        } catch {
            try skipIfKeychainPersistenceUnavailable(error)
            throw error
        }
        XCTAssertEqual(store.load(provider: .googleCloudTTS), [.apiKey: "second-key"])
    }

    func testClearRemovesCredentials() throws {
        let store = KeychainCloudApiKeyStore()
        do {
            try store.save(provider: .elevenLabs, credentials: [.apiKey: "to-be-cleared"])
        } catch {
            try skipIfKeychainPersistenceUnavailable(error)
            throw error
        }
        store.clear(provider: .elevenLabs)
        XCTAssertNil(store.load(provider: .elevenLabs))
    }

    /// A provider's stored credentials are independent of every other provider's —
    /// regression guard against a keying bug (e.g. a shared/static Keychain query
    /// missing `kSecAttrAccount`) that would make one provider's save clobber another's.
    func testCredentialsForDifferentProvidersDoNotCollide() throws {
        let store = KeychainCloudApiKeyStore()
        providersToClean.append(contentsOf: [.elevenLabs, .openAI])
        do {
            try store.save(provider: .elevenLabs, credentials: [.apiKey: "elevenlabs-key"])
            try store.save(provider: .openAI, credentials: [.apiKey: "openai-key"])
        } catch {
            try skipIfKeychainPersistenceUnavailable(error)
            throw error
        }
        XCTAssertEqual(store.load(provider: .elevenLabs), [.apiKey: "elevenlabs-key"])
        XCTAssertEqual(store.load(provider: .openAI), [.apiKey: "openai-key"])
    }
}
