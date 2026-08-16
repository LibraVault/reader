import Foundation

extension Data {
    /// Overwrites every byte with 0, in place. Used to scrub short-lived
    /// derived key material (e.g. the KEK, freshly unwrapped in
    /// `VaultKeyManager`) as soon as it's no longer needed - mirrors Android's
    /// `kek.fill(0)` in `VaultKeyManager.kt`'s `finally` blocks.
    ///
    /// This is a best-effort mitigation, not a guarantee: Swift's `Data` may
    /// have already made internal copies (e.g. during a `+` concatenation or a
    /// bridging conversion) before this call ever runs, and the optimizer is
    /// free to treat a write to a value about to go out of scope as dead code
    /// in principle. `memset` (rather than a Swift loop) is used specifically
    /// because it's a well-known compiler intrinsic unlikely to be elided the
    /// way a hand-written zeroing loop can be - the same caveat Android's own
    /// `ByteArray.fill(0)` carries against a sufficiently aggressive JIT.
    mutating func secureZero() {
        withUnsafeMutableBytes { raw in
            guard let base = raw.baseAddress else { return }
            memset(base, 0, raw.count)
        }
    }
}

extension Array where Element == UInt8 {
    /// Same as `Data.secureZero()`, for the `[UInt8]` buffers the Argon2 C API
    /// writes into directly (`Argon2idKdf.deriveKey`'s `output`) - copying
    /// that buffer into a `Data` to return does not by itself scrub the
    /// original array, so callers that scrub only the returned `Data` leave a
    /// second live copy of the derived key sitting in that array's storage.
    mutating func secureZero() {
        withUnsafeMutableBytes { raw in
            guard let base = raw.baseAddress else { return }
            memset(base, 0, raw.count)
        }
    }
}
