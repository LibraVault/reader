import XCTest
@testable import LibraVault

final class CloudCredentialFieldsTests: XCTestCase {

    func testElevenLabsRequiresOnlyApiKey() {
        XCTAssertEqual(CloudCredentialFields.requiredFields(for: .elevenLabs), [.apiKey])
    }

    func testOpenAIRequiresOnlyApiKey() {
        XCTAssertEqual(CloudCredentialFields.requiredFields(for: .openAI), [.apiKey])
    }

    func testGoogleCloudTTSRequiresOnlyApiKey() {
        XCTAssertEqual(CloudCredentialFields.requiredFields(for: .googleCloudTTS), [.apiKey])
    }

    func testAzureSpeechRequiresApiKeyAndRegion() {
        XCTAssertEqual(CloudCredentialFields.requiredFields(for: .azureSpeech), [.apiKey, .region])
    }

    /// The one vendor whose auth isn't a bearer/header key at all — SigV4 needs all
    /// three. Regression guard against this silently regressing to a single `.apiKey`
    /// the way a careless refactor of the `switch` in `requiredFields` could.
    func testAmazonPollyRequiresAccessKeySecretAndRegion() {
        XCTAssertEqual(
            CloudCredentialFields.requiredFields(for: .amazonPolly),
            [.accessKeyID, .secretAccessKey, .region]
        )
    }

    func testEveryProviderDisplayNameIsNonEmpty() {
        for provider in CloudProviderId.allCases {
            XCTAssertFalse(provider.displayName.isEmpty, "\(provider) has an empty displayName")
        }
    }
}
