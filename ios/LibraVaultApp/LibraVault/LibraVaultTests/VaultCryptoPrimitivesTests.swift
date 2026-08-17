import XCTest
@testable import LibraVault

/// Direct tests for the VaultCrypto primitives that had **zero** test
/// references (docs/TEST_COVERAGE_PRD.md, S1): `deriveFileContentKey`,
/// `BigEndian`, `SecureRandom` and `secureZero()`.
///
/// All four were previously exercised only indirectly, through vault
/// round-trips. That is the coverage shape that hides a defect, because a
/// round-trip agrees with itself no matter what these produce.
final class VaultCryptoPrimitivesTests: XCTestCase {

    // MARK: - deriveFileContentKey

    private let vmk = Data((0..<32).map { UInt8($0) })
    private let fileId = Data((0..<16).map { UInt8(0xA0 + $0) })
    private let otherFileId = Data((0..<16).map { UInt8(0xB0 + $0) })

    private func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }

    /// The same constant `FileContentKeyTest.kt` asserts.
    ///
    /// This is the cross-platform pin that matters most here: Android
    /// hand-rolls RFC 5869, iOS calls `CryptoKit.HKDF<SHA256>`. Two different
    /// implementations of one construction, so the composed result — salt, info
    /// prefix, fileId concatenation, output length — is what has to match.
    ///
    /// Computed independently (Python `hmac`/`hashlib`) from the documented
    /// inputs rather than captured from either implementation's output, so it
    /// is a genuine known-answer and not a snapshot of current behaviour.
    func testDerivesTheKnownCrossPlatformFileContentKey() {
        XCTAssertEqual(
            hex(deriveFileContentKey(vmk: vmk, fileId: fileId)),
            "fa5c385575f0b8cb445d5c430aad2a837ac73fcb8c783918aab0943a4187e038",
            "File content key derivation changed — this constant is shared with Android "
                + "(FileContentKeyTest.kt) and with every vault already on disk."
        )
    }

    func testDerivesADifferentKnownKeyForADifferentFileId() {
        XCTAssertEqual(
            hex(deriveFileContentKey(vmk: vmk, fileId: otherFileId)),
            "90e86286779495a2d98e58b239ae075b4c275a2a8bcfa3c4a9efda84629868ce"
        )
    }

    /// The security property the per-file key exists for: compromising one
    /// file's key must not help against another file.
    func testDifferentFileIdsYieldUnrelatedKeys() {
        XCTAssertNotEqual(
            deriveFileContentKey(vmk: vmk, fileId: fileId),
            deriveFileContentKey(vmk: vmk, fileId: otherFileId)
        )
    }

    func testDifferentVmksYieldDifferentKeys() {
        let otherVmk = Data((0..<32).map { UInt8(($0 + 1) % 256) })
        XCTAssertNotEqual(
            deriveFileContentKey(vmk: vmk, fileId: fileId),
            deriveFileContentKey(vmk: otherVmk, fileId: fileId)
        )
    }

    /// Keys are re-derived on every open and never stored, so this must be pure.
    func testDerivationIsDeterministic() {
        XCTAssertEqual(
            deriveFileContentKey(vmk: vmk, fileId: fileId),
            deriveFileContentKey(vmk: vmk, fileId: fileId)
        )
    }

    func testDerivedKeyIsExactlyTheVmkSize() {
        XCTAssertEqual(deriveFileContentKey(vmk: vmk, fileId: fileId).count, VaultFormat.vmkSizeBytes)
    }

    // MARK: - BigEndian

    /// These must match `java.nio.ByteBuffer`'s default byte order exactly —
    /// they decode the on-disk header Android writes.
    func testBigEndianUInt32RoundTripsAndMatchesJavaByteOrder() {
        XCTAssertEqual(BigEndian.bytes(ofUInt32: 0x0102_0304), [0x01, 0x02, 0x03, 0x04])
        XCTAssertEqual(BigEndian.uint32([0x01, 0x02, 0x03, 0x04], at: 0), 0x0102_0304)
        // Reading at a non-zero offset is the real usage (header field at byte 18).
        XCTAssertEqual(BigEndian.uint32([0xFF, 0xFF, 0x00, 0x00, 0x01, 0x00], at: 2), 0x0000_0100)
    }

    func testBigEndianInt32DecodesNegativeValuesViaBitPattern() {
        XCTAssertEqual(BigEndian.int32([0xFF, 0xFF, 0xFF, 0xFF], at: 0), -1)
        XCTAssertEqual(BigEndian.int32([0x80, 0x00, 0x00, 0x00], at: 0), Int32.min)
    }

    func testBigEndianInt64RoundTripsIncludingBoundaries() {
        for value in [Int64(0), 1, -1, 150, Int64.max, Int64.min] {
            let bytes = BigEndian.bytes(ofInt64: value)
            XCTAssertEqual(bytes.count, 8)
            XCTAssertEqual(BigEndian.int64(bytes, at: 0), value, "round-trip of \(value)")
        }
    }

    func testBigEndianInt64MatchesAKnownEncoding() {
        // 150 is the golden fixture's plaintext length, stored at header byte 22.
        XCTAssertEqual(BigEndian.bytes(ofInt64: 150), [0, 0, 0, 0, 0, 0, 0, 150])
    }

    // MARK: - SecureRandom

    func testSecureRandomReturnsTheRequestedLength() throws {
        for count in [1, 12, 16, 32, 64] {
            XCTAssertEqual(try SecureRandom.bytes(count: count).count, count)
        }
    }

    /// Not a randomness-quality test — `SecRandomCopyBytes` is the platform CSPRNG
    /// and testing its distribution here would be theatre. This only catches the
    /// concrete bug of a wrapper that returns a constant or a reused buffer,
    /// which is a real thing that happens.
    func testSecureRandomDoesNotRepeatAcrossCalls() throws {
        let samples = try (0..<8).map { _ in try SecureRandom.bytes(count: 32) }
        XCTAssertEqual(Set(samples).count, samples.count, "SecureRandom returned a duplicate block")
        XCTAssertNotEqual(samples[0], Data(repeating: 0, count: 32), "returned all zeros")
    }

    func testSecureRandomAcceptsZeroLength() throws {
        XCTAssertEqual(try SecureRandom.bytes(count: 0).count, 0)
    }

    // MARK: - secureZero

    /// What this can and cannot prove is worth being explicit about: it shows
    /// the buffer the caller still holds is zeroed, which is the part that is
    /// actually observable. It cannot prove the optimizer kept the write, nor
    /// that no earlier copy of the key survives elsewhere in memory —
    /// `SecureZero.swift`'s own doc comment is candid that this is best-effort.
    func testSecureZeroClearsDataInPlace() {
        var data = Data((0..<64).map { UInt8($0) })
        XCTAssertNotEqual(data, Data(repeating: 0, count: 64))
        data.secureZero()
        XCTAssertEqual(data, Data(repeating: 0, count: 64), "Data.secureZero left non-zero bytes")
    }

    func testSecureZeroClearsByteArrayInPlace() {
        var bytes = [UInt8]((0..<64).map { UInt8($0) })
        bytes.secureZero()
        XCTAssertEqual(bytes, [UInt8](repeating: 0, count: 64), "Array.secureZero left non-zero bytes")
    }

    /// The `[UInt8]` overload exists specifically because Argon2 writes into a
    /// raw array and copying it into a `Data` to return leaves a second live
    /// copy behind — scrubbing one must not be assumed to scrub the other.
    func testSecureZeroOnACopyDoesNotAffectTheOriginal() {
        var original = Data([1, 2, 3, 4])
        var copy = original
        copy.secureZero()
        XCTAssertEqual(original, Data([1, 2, 3, 4]), "value semantics: scrubbing a copy must not touch the original")
        original.secureZero()
        XCTAssertEqual(original, Data(repeating: 0, count: 4))
    }

    func testSecureZeroOnEmptyBufferIsSafe() {
        var empty = Data()
        empty.secureZero()
        XCTAssertEqual(empty.count, 0)
    }
}
