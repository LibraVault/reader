import XCTest
@testable import LibraVault

/// Proves the structural (not merely probabilistic) nonce-uniqueness claim in
/// PRD §8.2 point 5: deriving the nonce from a PRF keyed per-file, rather than
/// drawing it from a CSPRNG, means a collision under a fixed key requires an
/// HMAC-SHA256 collision, not a 96-bit birthday collision. Mirrors Android's
/// `NonceUniquenessTest.kt`.
final class NonceUniquenessTests: XCTestCase {

    func testSameKeyManyChunkIndicesProducesAllDistinctNonces() {
        let key = VaultCryptoTestSupport.randomData(32)
        var seen = Set<Data>()
        for index in 0..<100_000 {
            let nonce = deriveNonce(fileContentKey: key, chunkIndex: Int64(index))
            let (inserted, _) = seen.insert(nonce)
            XCTAssertTrue(inserted, "found a nonce collision within one file's own chunk range at index \(index)")
        }
        XCTAssertEqual(100_000, seen.count)
    }

    func testIsDeterministicSameKeyAndIndexAlwaysDeriveTheSameNonce() {
        let key = VaultCryptoTestSupport.randomData(32)
        XCTAssertEqual(deriveNonce(fileContentKey: key, chunkIndex: 42), deriveNonce(fileContentKey: key, chunkIndex: 42))
    }

    func testDifferentFilesDifferentKeysOccupyDifferentNonceSpacesAtTheSameIndex() {
        let keyA = VaultCryptoTestSupport.randomData(32)
        let keyB = VaultCryptoTestSupport.randomData(32)
        XCTAssertNotEqual(deriveNonce(fileContentKey: keyA, chunkIndex: 0), deriveNonce(fileContentKey: keyB, chunkIndex: 0))
    }

    func testNonceIsExactlyNonceSizeBytesLong() {
        let key = VaultCryptoTestSupport.randomData(32)
        XCTAssertEqual(VaultFormat.nonceSizeBytes, deriveNonce(fileContentKey: key, chunkIndex: 0).count)
    }
}
