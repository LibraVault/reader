import Foundation
import Security

/// Real, Keychain-backed `CloudApiKeyStore`. One `kSecClassGenericPassword` item per
/// provider, keyed by `kSecAttrAccount` = the provider's raw value under a fixed
/// `kSecAttrService` — a different shape from `SecureEnclaveHardwareKeyWrapFactory`'s
/// `kSecClassKey` items (that class stores an actual EC keypair; this stores an
/// arbitrary opaque secret, which `kSecClassGenericPassword` is Apple's documented class
/// for).
///
/// Deliberately does NOT additionally wrap the value through `HardwareKeyWrap`/the
/// Secure Enclave the way Android's `RealCloudApiKeyStore` wraps through
/// `HardwareKeyWrapFactory` before writing to DataStore — that extra layer exists on
/// Android because a DataStore file is plain app-private storage with no OS-level
/// encryption-at-rest of its own. The iOS Keychain already provides encryption-at-rest
/// itself (backed by the Secure Enclave-derived class keys, the same hierarchy
/// `SecureEnclaveHardwareKeyWrap` sits on top of for the vault master key), and
/// `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` below is the standard, sufficient
/// pattern for a secret like a BYOK vendor API key — unlike the vault master key, this
/// isn't LibraVault's root of trust, so no extra ECIES wrapping layer is warranted here.
///
/// Credentials are JSON-encoded as `[String: String]` (not `[CloudCredentialField:
/// String]` directly) before being handed to `JSONEncoder` — dictionaries keyed by a
/// custom `RawRepresentable` enum aren't guaranteed to encode as a JSON object across
/// Codable's synthesized behavior, so the field/value pairs are converted to plain
/// `String` keys first, matching a pattern already proven safe rather than relying on
/// an implementation detail.
final class KeychainCloudApiKeyStore: CloudApiKeyStore {

    private static let service = "xyz.libravault.cloudtts.apikey"

    func save(provider: CloudProviderId, credentials: [CloudCredentialField: String]) throws {
        let requiredFields = CloudCredentialFields.requiredFields(for: provider)
        guard Set(credentials.keys) == requiredFields else {
            throw CloudApiKeyStoreError.fieldMismatch(provider: provider)
        }

        let plainKeyed = Dictionary(uniqueKeysWithValues: credentials.map { ($0.key.rawValue, $0.value) })
        let data: Data
        do {
            data = try JSONEncoder().encode(plainKeyed)
        } catch {
            // JSONEncoder failing on a [String: String] would mean something is
            // fundamentally broken about the runtime, not a real recoverable case —
            // there's no vendor-specific reason this should ever throw, so it's
            // surfaced as the same keychainError case with status 0 rather than adding
            // a third error case nothing else in this type could ever produce.
            throw CloudApiKeyStoreError.keychainError(status: 0)
        }

        // Delete-then-add, not SecItemUpdate: mirrors
        // SecureEnclaveHardwareKeyWrapFactory.createNew's "any existing item under this
        // identity is replaced" contract — simpler than branching on whether an item
        // already exists, and this is a cheap, infrequent (user-initiated) operation.
        deleteItem(provider: provider)

        var query = keychainQuery(provider: provider)
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly

        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw CloudApiKeyStoreError.keychainError(status: status)
        }
    }

    func load(provider: CloudProviderId) -> [CloudCredentialField: String]? {
        var query = keychainQuery(provider: provider)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return nil }

        guard let plainKeyed = try? JSONDecoder().decode([String: String].self, from: data) else {
            return nil
        }
        // A field name that no longer maps to a known CloudCredentialField (e.g. a
        // stale Keychain item from a since-removed field) is dropped rather than
        // failing the whole load — the caller's own required-fields check downstream
        // (CloudTtsEngine/onValidateAndSaveCloudKey-equivalent) is what should reject a
        // now-incomplete credential set, not this layer silently returning nil for an
        // otherwise-readable item.
        return Dictionary(uniqueKeysWithValues: plainKeyed.compactMap { key, value in
            CloudCredentialField(rawValue: key).map { ($0, value) }
        })
    }

    func clear(provider: CloudProviderId) {
        deleteItem(provider: provider)
    }

    private func deleteItem(provider: CloudProviderId) {
        SecItemDelete(keychainQuery(provider: provider) as CFDictionary)
    }

    private func keychainQuery(provider: CloudProviderId) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: provider.rawValue,
        ]
    }
}
