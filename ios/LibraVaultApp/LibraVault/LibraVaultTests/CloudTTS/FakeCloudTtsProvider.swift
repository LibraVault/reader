import Foundation
@testable import LibraVault

/// Test-only `CloudTtsProvider` — lets tests control `validateKey`/`synthesize`
/// outcomes without a real network call. Mirrors `FakeCloudApiKeyStore`'s shape
/// (behaves like the real thing for the properties tests care about, no real
/// system/network dependency).
final class FakeCloudTtsProvider: CloudTtsProvider {
    var validateKeyError: Error?
    var synthesizeResult: Result<Data, Error> = .success(Data())
    private(set) var validateKeyCallCount = 0
    private(set) var synthesizeCallCount = 0

    func synthesize(
        provider: CloudProviderId,
        text: String,
        voiceID: String,
        credentials: [CloudCredentialField: String]
    ) async throws -> Data {
        synthesizeCallCount += 1
        return try synthesizeResult.get()
    }

    func validateKey(provider: CloudProviderId, credentials: [CloudCredentialField: String]) async throws {
        validateKeyCallCount += 1
        if let validateKeyError {
            throw validateKeyError
        }
    }
}
