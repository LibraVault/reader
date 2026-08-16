import Foundation
import Security

/// Shared `SecRandomCopyBytes` wrapper - both `KeyWrap` (fresh wrap nonces)
/// and `VaultKeyManager` (VMK/recovery key/Argon2 salt generation) need
/// "N cryptographically random bytes or a catchable error," so this exists
/// once instead of each call site re-checking `errSecSuccess` by hand.
enum SecureRandom {

    static func bytes(count: Int) throws -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        let status = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        guard status == errSecSuccess else {
            throw VaultCryptoError.randomGenerationFailed(status: status)
        }
        return Data(bytes)
    }
}
