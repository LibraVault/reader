import Foundation

/// Argon2id parameters for deriving the Key-Encryption-Key from a PIN/passphrase.
///
/// Stored per-vault in the format header (PRD §8.2b), NOT hardcoded globally -
/// this is what lets future releases change the default without breaking vaults
/// already created under an older one; only new vaults pick up a new default.
///
/// `defaultParams` is 19 MiB / t=2 / p=1, mirroring Android's provisional default
/// (PRD §8.4b) exactly - see `Argon2idKdfTest`'s doc comment on Android for the
/// on-device benchmark reasoning behind these numbers. It's deliberately lighter
/// than a "maximum security" profile would otherwise call for: the Secure
/// Enclave hardware wrap layered on top of this (see the handoff spec's
/// `HardwareKeyWrap` design, Phase 2) already removes the offline brute-force
/// attack this KDF exists to slow down. Argon2id's job here is defence-in-depth
/// if that wrap is ever bypassed, not the primary defense.
///
/// TODO(Phase 1 follow-up): confirm this default with an on-device benchmark on
/// real iPhone hardware before shipping - don't assume Android's numbers
/// transfer (different CPU architecture, different Argon2 backend: BouncyCastle
/// on Android vs. this module's vendored reference C implementation here).
struct Argon2Params: Equatable {
    let memoryKiB: Int
    let iterations: Int
    let parallelism: Int

    static let defaultParams = Argon2Params(memoryKiB: 19 * 1024, iterations: 2, parallelism: 1)
}

/// Derives a key from a PIN/passphrase and salt using Argon2id, via the
/// vendored P-H-C reference C implementation (see ThirdParty/Argon2).
///
/// The caller is responsible for zeroing `pin` after use - this function does
/// not retain a copy. `pin` is `[UInt8]` (its UTF-8 bytes), not `String`:
/// Swift Strings are immutable, may be copied internally by the runtime, and
/// offer no way to guarantee zeroing, the same reasoning as Android's ban on
/// passing the PIN as a Kotlin `String` there.
enum Argon2idKdf {

    static func deriveKey(pin: [UInt8], salt: Data, params: Argon2Params, outputLengthBytes: Int = 32) throws -> Data {
        precondition(salt.count == VaultFormat.argon2SaltSizeBytes, "salt must be \(VaultFormat.argon2SaltSizeBytes) bytes")

        var output = [UInt8](repeating: 0, count: outputLengthBytes)
        // `Data(output)` below copies these bytes into a new heap buffer for the
        // return value - that copy doesn't zero `output` itself, so without this
        // defer the derived key would still exist unscrubbed in this array's
        // storage even after a caller diligently zeroes the `Data` they got back.
        defer { output.secureZero() }
        let saltBytes = [UInt8](salt)

        // argon2id_hash_raw internally always uses ARGON2_VERSION_NUMBER
        // (0x13 / ARGON2_VERSION_13) - the same version Android's
        // Argon2Parameters.Builder.withVersion(ARGON2_VERSION_13) pins
        // explicitly - so both platforms derive identical keys from
        // identical (pin, salt, params).
        let returnCode: Int32 = pin.withUnsafeBufferPointer { pinBuf in
            saltBytes.withUnsafeBufferPointer { saltBuf in
                output.withUnsafeMutableBufferPointer { outBuf in
                    argon2id_hash_raw(
                        UInt32(params.iterations),
                        UInt32(params.memoryKiB),
                        UInt32(params.parallelism),
                        pinBuf.baseAddress, pinBuf.count,
                        saltBuf.baseAddress, saltBuf.count,
                        outBuf.baseAddress, outBuf.count
                    )
                }
            }
        }

        // ARGON2_OK == 0 (include/argon2.h) - compared as a literal rather than
        // against the imported `ARGON2_OK` symbol so this doesn't depend on
        // exactly how the Clang importer represents that C enum in Swift.
        guard returnCode == 0 else {
            let message = String(cString: argon2_error_message(returnCode))
            throw VaultCryptoError.argon2Failed(code: returnCode, message: message)
        }

        return Data(output)
    }
}
