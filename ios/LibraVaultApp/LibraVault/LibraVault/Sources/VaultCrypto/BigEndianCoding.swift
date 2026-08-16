import Foundation

/// Manual big-endian integer encode/decode over `[UInt8]`, matching
/// `java.nio.ByteBuffer`'s default (big-endian) byte order used throughout
/// Android core:vaultcrypto's on-disk header/AAD layout.
///
/// Deliberately byte-by-byte rather than `UnsafeRawPointer.load(as:)` — the
/// latter requires the pointer to be correctly aligned for the loaded type,
/// which isn't guaranteed for an arbitrary offset into bytes read off disk; a
/// misaligned load is undefined behavior, not just slow. Operating on
/// `[UInt8]` (always 0-based `Int` indices) rather than `Data` directly also
/// sidesteps `Data`'s non-guaranteed-zero `startIndex` after slicing, which
/// has bitten real code before.
enum BigEndian {

    static func bytes(ofUInt32 value: UInt32) -> [UInt8] {
        [
            UInt8((value >> 24) & 0xFF),
            UInt8((value >> 16) & 0xFF),
            UInt8((value >> 8) & 0xFF),
            UInt8(value & 0xFF),
        ]
    }

    static func bytes(ofInt64 value: Int64) -> [UInt8] {
        let u = UInt64(bitPattern: value)
        return (0..<8).map { i in UInt8((u >> (8 * (7 - i))) & 0xFF) }
    }

    /// Reads a 4-byte big-endian value starting at `offset` in `bytes` as a signed Int32.
    static func int32(_ bytes: [UInt8], at offset: Int) -> Int32 {
        Int32(bitPattern: uint32(bytes, at: offset))
    }

    static func uint32(_ bytes: [UInt8], at offset: Int) -> UInt32 {
        (UInt32(bytes[offset]) << 24) | (UInt32(bytes[offset + 1]) << 16)
            | (UInt32(bytes[offset + 2]) << 8) | UInt32(bytes[offset + 3])
    }

    /// Reads an 8-byte big-endian value starting at `offset` in `bytes` as a signed Int64.
    static func int64(_ bytes: [UInt8], at offset: Int) -> Int64 {
        var v: UInt64 = 0
        for k in 0..<8 { v = (v << 8) | UInt64(bytes[offset + k]) }
        return Int64(bitPattern: v)
    }
}
