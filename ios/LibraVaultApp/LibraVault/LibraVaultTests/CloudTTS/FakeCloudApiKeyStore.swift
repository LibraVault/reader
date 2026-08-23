import Foundation
@testable import LibraVault

/// Test-only in-memory `CloudApiKeyStore` — for tests exercising code that depends on
/// this protocol (Settings view models, `CloudTtsEngine`) without touching the real
/// Keychain. Mirrors Android's `FakeCloudApiKeyStore` testing double (implicit via MockK
/// in `SettingsViewModelTest`) and this codebase's own `FakeHardwareKeyWrapFactory`
/// pattern: behaves like the real thing for the properties tests care about (per-provider
/// storage, field-mismatch rejection) without any real system dependency.
final class FakeCloudApiKeyStore: CloudApiKeyStore {
    private var storage: [CloudProviderId: [CloudCredentialField: String]] = [:]

    func save(provider: CloudProviderId, credentials: [CloudCredentialField: String]) throws {
        guard Set(credentials.keys) == CloudCredentialFields.requiredFields(for: provider) else {
            throw CloudApiKeyStoreError.fieldMismatch(provider: provider)
        }
        storage[provider] = credentials
    }

    func load(provider: CloudProviderId) -> [CloudCredentialField: String]? {
        storage[provider]
    }

    func clear(provider: CloudProviderId) {
        storage.removeValue(forKey: provider)
    }
}
