import XCTest
@testable import LibraVault

/// Mirrors Android's `ChunkedVaultRoundTripTest.kt`.
final class ChunkedVaultRoundTripTests: XCTestCase {

    private let vmk = VaultCryptoTestSupport.randomData(32)
    private let fileId = VaultCryptoTestSupport.randomData(16)
    private let smallChunkSize = 64 // tiny, so tests exercise many chunk boundaries cheaply

    func testRoundTripsWholeFileForVariousSizesRelativeToChunkBoundaries() throws {
        for size in [0, 1, 63, 64, 65, 127, 128, 129, 1000] {
            let plain = VaultCryptoTestSupport.randomData(size)
            let url = try VaultCryptoTestSupport.encryptToTempFile(vmk: vmk, fileId: fileId, plain: plain, chunkSize: smallChunkSize)
            defer { try? FileManager.default.removeItem(at: url) }

            let reader = try VaultFileReader(fileURL: url, vmk: vmk, expectedFileId: fileId)
            defer { reader.close() }
            XCTAssertEqual(Int64(size), reader.plainSize, "size=\(size)")
            XCTAssertEqual(plain, try reader.readAt(offset: 0, length: size), "size=\(size)")
        }
    }

    func testRandomAccessAcrossAChunkBoundaryReturnsExactlyTheRequestedBytes() throws {
        let plain = VaultCryptoTestSupport.randomData(smallChunkSize * 3 + 10)
        let url = try VaultCryptoTestSupport.encryptToTempFile(vmk: vmk, fileId: fileId, plain: plain, chunkSize: smallChunkSize)
        defer { try? FileManager.default.removeItem(at: url) }

        let reader = try VaultFileReader(fileURL: url, vmk: vmk, expectedFileId: fileId)
        defer { reader.close() }
        let off = smallChunkSize - 5
        let expected = plain[plain.index(plain.startIndex, offsetBy: off)..<plain.index(plain.startIndex, offsetBy: off + 20)]
        XCTAssertEqual(Data(expected), try reader.readAt(offset: Int64(off), length: 20))
    }

    func testReadingTheTailReturnsExactlyTheRemainingBytesNotMore() throws {
        let plain = VaultCryptoTestSupport.randomData(smallChunkSize * 2 + 7)
        let url = try VaultCryptoTestSupport.encryptToTempFile(vmk: vmk, fileId: fileId, plain: plain, chunkSize: smallChunkSize)
        defer { try? FileManager.default.removeItem(at: url) }

        let reader = try VaultFileReader(fileURL: url, vmk: vmk, expectedFileId: fileId)
        defer { reader.close() }
        let tailOff = plain.count - 3
        let result = try reader.readAt(offset: Int64(tailOff), length: 100) // ask for more than exists
        XCTAssertEqual(Data(plain[plain.index(plain.startIndex, offsetBy: tailOff)...]), result)
    }

    func testSequentialReadsWithinOneChunkHitTheCacheNotAFreshDecrypt() throws {
        let plain = VaultCryptoTestSupport.randomData(smallChunkSize * 2)
        let url = try VaultCryptoTestSupport.encryptToTempFile(vmk: vmk, fileId: fileId, plain: plain, chunkSize: smallChunkSize)
        defer { try? FileManager.default.removeItem(at: url) }

        let reader = try VaultFileReader(fileURL: url, vmk: vmk, expectedFileId: fileId)
        defer { reader.close() }
        _ = try reader.readAt(offset: 0, length: 10)
        _ = try reader.readAt(offset: 10, length: 10)
        _ = try reader.readAt(offset: 20, length: 10)
        // All three reads are within chunk 0 - should be exactly one real decrypt.
        XCTAssertEqual(1, reader.decryptCount)
    }

    func testReadingTwoDifferentChunksDecryptsExactlyTwice() throws {
        let plain = VaultCryptoTestSupport.randomData(smallChunkSize * 2)
        let url = try VaultCryptoTestSupport.encryptToTempFile(vmk: vmk, fileId: fileId, plain: plain, chunkSize: smallChunkSize)
        defer { try? FileManager.default.removeItem(at: url) }

        let reader = try VaultFileReader(fileURL: url, vmk: vmk, expectedFileId: fileId)
        defer { reader.close() }
        _ = try reader.readAt(offset: 0, length: 1)
        _ = try reader.readAt(offset: Int64(smallChunkSize), length: 1)
        XCTAssertEqual(2, reader.decryptCount)
    }

    func testWrongVmkFailsToDecryptEvenACorrectlyFormedFile() throws {
        let plain = VaultCryptoTestSupport.randomData(smallChunkSize + 1)
        let url = try VaultCryptoTestSupport.encryptToTempFile(vmk: vmk, fileId: fileId, plain: plain, chunkSize: smallChunkSize)
        defer { try? FileManager.default.removeItem(at: url) }
        let wrongVmk = VaultCryptoTestSupport.randomData(32)

        // Kotlin's equivalent asserts the throw happens on the *first read* (Android's
        // reader lazily decrypts chunk 0 there); this port eagerly decrypts chunk 0 in
        // init (see VaultFileReader's doc comment) specifically to close the "erase by
        // truncation" gap, so the wrong-VMK failure surfaces at construction time here
        // instead - a strictly earlier, still-correct failure point.
        XCTAssertThrowsError(try VaultFileReader(fileURL: url, vmk: wrongVmk, expectedFileId: fileId)) { error in
            XCTAssertEqual(.authenticationFailed, error as? VaultCryptoError)
        }
    }
}
