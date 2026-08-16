import Foundation

/// Big-endian integer encode/decode matching `java.nio.ByteBuffer`'s default
/// (big-endian) byte order, used throughout Android core:vaultcrypto's
/// on-disk header/AAD layout.
enum BigEndian {

    /// Encoding a local (already correctly-aligned) value has no alignment
    /// hazard, so this can safely use the stdlib's `FixedWidthInteger.bigEndian`
    /// + `withUnsafeBytes(of:)` rather than hand-rolled shifting - unlike
    /// *decoding* below, which reads from an arbitrary offset into bytes read
    /// off disk and deliberately avoids `UnsafeRawPointer.load(as:)` for that
    /// reason (a misaligned load there is undefined behavior, not just slow).
    static func bytes(ofUInt32 value: UInt32) -> [UInt8] {
        withUnsafeBytes(of: value.bigEndian) { Array($0) }
    }

    static func bytes(ofInt64 value: Int64) -> [UInt8] {
        withUnsafeBytes(of: value.bigEndian) { Array($0) }
    }

    /// Reads a 4-byte big-endian value starting at `offset` in `bytes` as a signed Int32.
    ///
    /// Deliberately byte-by-byte (not `UnsafeRawPointer.load(as:)`, which
    /// requires correct alignment for the loaded type - not guaranteed for an
    /// arbitrary offset into bytes read off disk). Operating on `[UInt8]`
    /// (always 0-based `Int` indices) rather than `Data` directly also
    /// sidesteps `Data`'s non-guaranteed-zero `startIndex` after slicing,
    /// which has bitten real code before.
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
