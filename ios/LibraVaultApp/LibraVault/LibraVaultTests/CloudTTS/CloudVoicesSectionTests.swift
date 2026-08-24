import XCTest
@testable import LibraVault

/// Exercises `CloudVoicesSection`'s extracted `static` logic directly — this project has
/// no ViewInspector/snapshot UI-testing infrastructure, so no test here can prove
/// SwiftUI's `body` actually renders these values on screen (see
/// `EncryptedVaultContentsViewTests`'s doc comment for the established precedent this
/// mirrors). The view itself stays a thin wrapper around these functions.
final class CloudVoicesSectionTests: XCTestCase {

    // MARK: - isSecureField

    func testRegionFieldIsNotSecure() {
        XCTAssertFalse(CloudVoicesSection.isSecureField(.region))
    }

    func testApiKeyAndAwsCredentialFieldsAreSecure() {
        XCTAssertTrue(CloudVoicesSection.isSecureField(.apiKey))
        XCTAssertTrue(CloudVoicesSection.isSecureField(.accessKeyID))
        XCTAssertTrue(CloudVoicesSection.isSecureField(.secretAccessKey))
    }

    // MARK: - isSaveEnabled

    func testSaveDisabledWhenARequiredFieldIsBlank() {
        let required: Set<CloudCredentialField> = [.apiKey, .region]
        let values: [CloudCredentialField: String] = [.apiKey: "key", .region: ""]
        XCTAssertFalse(CloudVoicesSection.isSaveEnabled(requiredFields: required, values: values, isValidating: false))
    }

    func testSaveDisabledWhenARequiredFieldIsMissingEntirely() {
        let required: Set<CloudCredentialField> = [.apiKey, .region]
        XCTAssertFalse(CloudVoicesSection.isSaveEnabled(requiredFields: required, values: [.apiKey: "key"], isValidating: false))
    }

    func testSaveDisabledWhenARequiredFieldIsWhitespaceOnly() {
        let required: Set<CloudCredentialField> = [.apiKey]
        XCTAssertFalse(CloudVoicesSection.isSaveEnabled(requiredFields: required, values: [.apiKey: "   "], isValidating: false))
    }

    func testSaveDisabledWhileValidatingEvenWithAllFieldsFilled() {
        let required: Set<CloudCredentialField> = [.apiKey]
        XCTAssertFalse(CloudVoicesSection.isSaveEnabled(requiredFields: required, values: [.apiKey: "sk-real"], isValidating: true))
    }

    func testSaveEnabledWhenAllRequiredFieldsAreFilledAndNotValidating() {
        let required: Set<CloudCredentialField> = [.accessKeyID, .secretAccessKey, .region]
        let values: [CloudCredentialField: String] = [
            .accessKeyID: "AKIAEXAMPLE",
            .secretAccessKey: "shh-its-a-secret",
            .region: "us-east-1",
        ]
        XCTAssertTrue(CloudVoicesSection.isSaveEnabled(requiredFields: required, values: values, isValidating: false))
    }

    // MARK: - validateAndSave

    func testValidateAndSaveSavesCredentialsWhenValidationSucceeds() async {
        let ttsProvider = FakeCloudTtsProvider()
        let keyStore = FakeCloudApiKeyStore()
        let credentials: [CloudCredentialField: String] = [.apiKey: "sk-real"]

        let result = await CloudVoicesSection.validateAndSave(
            provider: .openAI, credentials: credentials, using: ttsProvider, keyStore: keyStore
        )

        guard case .success = result else { return XCTFail("expected success") }
        XCTAssertEqual(keyStore.load(provider: .openAI), credentials)
        XCTAssertEqual(ttsProvider.validateKeyCallCount, 1)
    }

    /// The single most safety-critical assertion here (PRD §6: "validated ... then
    /// stored") — a failed validation must never reach the key store at all.
    func testValidateAndSaveNeverSavesWhenValidationFails() async {
        let ttsProvider = FakeCloudTtsProvider()
        ttsProvider.validateKeyError = CloudTtsProviderError.httpError(statusCode: 401, body: "unauthorized")
        let keyStore = FakeCloudApiKeyStore()

        let result = await CloudVoicesSection.validateAndSave(
            provider: .elevenLabs, credentials: [.apiKey: "bad-key"], using: ttsProvider, keyStore: keyStore
        )

        guard case .failure = result else { return XCTFail("expected failure") }
        XCTAssertNil(keyStore.load(provider: .elevenLabs))
    }

    /// A key-store-side failure (e.g. a field-count mismatch) after validation already
    /// succeeded is still surfaced as a failure, not silently swallowed.
    func testValidateAndSavePropagatesKeyStoreFailureAfterValidationSucceeds() async {
        let ttsProvider = FakeCloudTtsProvider()
        let keyStore = FakeCloudApiKeyStore()

        let result = await CloudVoicesSection.validateAndSave(
            provider: .openAI,
            credentials: [.accessKeyID: "a", .secretAccessKey: "b", .region: "us-east-1"],
            using: ttsProvider,
            keyStore: keyStore
        )

        guard case .failure = result else { return XCTFail("expected failure") }
        XCTAssertEqual(ttsProvider.validateKeyCallCount, 1)
        XCTAssertNil(keyStore.load(provider: .openAI))
    }

    // MARK: - loadConfiguredProviders

    func testLoadConfiguredProvidersReturnsOnlyProvidersWithSavedCredentials() throws {
        let keyStore = FakeCloudApiKeyStore()
        try keyStore.save(provider: .openAI, credentials: [.apiKey: "sk-real"])

        XCTAssertEqual(CloudVoicesSection.loadConfiguredProviders(keyStore: keyStore), [.openAI])
    }

    func testLoadConfiguredProvidersIsEmptyWhenNothingSaved() {
        XCTAssertTrue(CloudVoicesSection.loadConfiguredProviders(keyStore: FakeCloudApiKeyStore()).isEmpty)
    }

    func testLoadConfiguredProvidersReflectsMultipleSavedProviders() throws {
        let keyStore = FakeCloudApiKeyStore()
        try keyStore.save(provider: .elevenLabs, credentials: [.apiKey: "a"])
        try keyStore.save(provider: .azureSpeech, credentials: [.apiKey: "b", .region: "eastus"])

        XCTAssertEqual(CloudVoicesSection.loadConfiguredProviders(keyStore: keyStore), [.elevenLabs, .azureSpeech])
    }
}
