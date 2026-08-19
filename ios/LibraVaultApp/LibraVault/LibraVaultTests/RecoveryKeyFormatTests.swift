import XCTest
@testable import LibraVault

final class RecoveryKeyFormatTests: XCTestCase {

    func testRoundTripsA32ByteKey() throws {
        let key = try SecureRandom.bytes(count: 32)
        let display = RecoveryKeyFormat.toDisplayString(key)
        let parsed = try XCTUnwrap(RecoveryKeyFormat.parse(display))
        XCTAssertEqual(parsed, key)
    }

    func testRoundTripsEveryByteValueAtLeastOnce() throws {
        // A key containing every possible byte value, not random — a
        // regression guard against an encode/decode bug that only manifests
        // for specific bit patterns (e.g. an off-by-one in the leftover-bits
        // handling) that random data might get lucky and never hit.
        let key = Data((0...255).map { UInt8($0) })
        let display = RecoveryKeyFormat.toDisplayString(key)
        let parsed = try XCTUnwrap(RecoveryKeyFormat.parse(display))
        XCTAssertEqual(parsed, key)
    }

    func testDisplayStringIsGroupedInFourCharacterBlocksSeparatedByHyphens() throws {
        let key = try SecureRandom.bytes(count: 32)
        let display = RecoveryKeyFormat.toDisplayString(key)
        let groups = display.split(separator: "-")
        XCTAssertTrue(groups.allSatisfy { $0.count == 4 })
        XCTAssertEqual(groups.joined().count, 52) // ceil(256/5)
    }

    func testParseIsCaseInsensitive() throws {
        let key = try SecureRandom.bytes(count: 32)
        let display = RecoveryKeyFormat.toDisplayString(key)
        let lowercased = display.lowercased()
        XCTAssertEqual(RecoveryKeyFormat.parse(lowercased), RecoveryKeyFormat.parse(display))
    }

    func testParseToleratesArbitraryWhitespaceAndSeparators() throws {
        let key = try SecureRandom.bytes(count: 32)
        let display = RecoveryKeyFormat.toDisplayString(key)
        let messy = "  " + display.replacingOccurrences(of: "-", with: " \n ") + "  "
        XCTAssertEqual(RecoveryKeyFormat.parse(messy), key)
    }

    func testParseOfEmptyStringReturnsNil() {
        XCTAssertNil(RecoveryKeyFormat.parse(""))
    }

    func testParseOfPureNoiseReturnsNil() {
        // Only separators/whitespace, no actual alphabet characters left
        // after stripping — nothing to decode.
        XCTAssertNil(RecoveryKeyFormat.parse("----   ----"))
    }

    func testParseRejectsATruncatedSymbolWithNonZeroLeftoverBits() {
        // A single Base32 character encodes 5 bits — with no bytes'-worth of
        // real data behind it, the leftover bits must be zero padding. "B" a
        // one-character input whose leftover bits are non-zero is corrupt,
        // not just short, and must be rejected rather than silently decoded
        // into a wrong, truncated key.
        XCTAssertNil(RecoveryKeyFormat.parse("B"))
    }

    func testAlphabetExcludesDigitsZeroAndOneEntirely() {
        // The whole point of choosing Base32 over hex: nothing in valid
        // output can ever be confused with a handwritten 0/O or 1/I, because
        // 0 and 1 never appear in the alphabet's output at all.
        for key in [Data([0x00]), Data([0xFF]), Data(repeating: 0x11, count: 4)] {
            let display = RecoveryKeyFormat.toDisplayString(key)
            XCTAssertFalse(display.contains("0"))
            XCTAssertFalse(display.contains("1"))
        }
    }
}
