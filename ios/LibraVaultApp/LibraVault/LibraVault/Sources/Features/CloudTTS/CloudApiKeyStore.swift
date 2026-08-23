import Foundation

/// iOS counterpart to Android's `CloudApiKeyStore` interface (core/cloudtts/CloudApiKeyStore.kt)
/// — a protocol, not a direct Keychain call at every call site, for the same reason
/// `HardwareKeyWrap` is one (see that file's doc comment): fakeable in plain unit tests
/// without touching the real Keychain. Credentials are a `[CloudCredentialField: String]`
/// map, not a single `apiKey: String`, matching Android's identical shape (see
/// `CloudCredentialFields`'s doc comment for why).
protocol CloudApiKeyStore {
    /// Overwrites any existing credentials for `provider`. Callers (see
    /// `CloudApiKeyStoreError`) are expected to have already validated the credentials
    /// against the real vendor API (PRD §6: "validated with a single cheap test call,
    /// then stored") — this method itself does no validation, only storage.
    func save(provider: CloudProviderId, credentials: [CloudCredentialField: String]) throws

    /// `nil` if nothing has been saved for `provider` yet.
    func load(provider: CloudProviderId) -> [CloudCredentialField: String]?

    /// No-op if nothing was saved for `provider`.
    func clear(provider: CloudProviderId)
}

/// Errors `CloudApiKeyStore` conformers can throw.
enum CloudApiKeyStoreError: LocalizedError, Equatable {
    /// `credentials`' keys didn't exactly match `CloudCredentialFields.requiredFields`
    /// for the given provider — mirrors Android's `RealCloudApiKeyStore.saveCredentials`
    /// `require()` check, catching a caller bug (a missing/extra field) before it's
    /// silently stored in a shape a vendor adapter can't actually use.
    case fieldMismatch(provider: CloudProviderId)

    /// A Keychain operation (`SecItemAdd`/`SecItemUpdate`/`SecItemDelete`) failed for a
    /// reason other than "item not found."
    case keychainError(status: OSStatus)

    var errorDescription: String? {
        switch self {
        case .fieldMismatch(let provider):
            return "Credentials for \(provider.displayName) don't match its required fields"
        case .keychainError(let status):
            return "Keychain operation failed (OSStatus \(status))"
        }
    }
}
