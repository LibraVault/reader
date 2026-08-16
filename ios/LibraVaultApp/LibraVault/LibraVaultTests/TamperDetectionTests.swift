import XCTest
@testable import LibraVault

/// Proves the tamper-defense claims in PRD §8.2/§8.2b: reordering, splicing,
/// and truncation (both mid-chunk and chunk-boundary-aligned) all fail rather
/// than silently succeeding, and every header field is authenticated because
/// it's bound into every chunk's AAD. Mirrors Android's `TamperDetectionTest.kt`.
final class TamperDetectionTests: XCTestCase {

    private let vmk = VaultCryptoTestSupport.randomData(32)
    private let fileId = VaultCryptoTestSupport.randomData(16)
    private let chunkSize = 64

    private func encryptedBytes(_ plain: Data) throws -> Data {
        try VaultCryptoTestSupport.encryptedBytes(vmk: vmk, fileId: fileId, plain: plain, chunkSize: chunkSize)
    }

    private func flip(_ data: Data, at index: Int) -> Data {
        var mutated = data
        let i = mutated.index(mutated.startIndex, offsetBy: index)
        mutated[i] ^= 0x01
        return mutated
    }

    private func assertAuthFailure(_ url: URL) {
        defer { try? FileManager.default.removeItem(at: url) }
        XCTAssertThrowsError(try VaultFileReader(fileURL: url, vmk: vmk, expectedFileId: fileId)) { error in
            XCTAssertEqual(.authenticationFailed, error as? VaultCryptoError)
        }
    }

    private func assertTruncated(_ url: URL) {
        defer { try? FileManager.default.removeItem(at: url) }
        XCTAssertThrowsError(try VaultFileReader(fileURL: url, vmk: vmk, expectedFileId: fileId)) { error in
            guard case .truncated = error as? VaultCryptoError else {
                XCTFail("expected .truncated, got \(error)")
                return
            }
        }
    }

    func testFlippingACiphertextByteInsideAChunkIsDetected() throws {
        let plain = VaultCryptoTestSupport.randomData(chunkSize * 2)
        let bytes = try encryptedBytes(plain)
        let tampered = flip(bytes, at: VaultFormat.headerSizeBytes + 5)
        assertAuthFailure(VaultCryptoTestSupport.writeTempFile(tampered))
    }

    func testFlippingATagByteIsDetected() throws {
        let plain = VaultCryptoTestSupport.randomData(chunkSize - 10) // exactly 1 chunk
        let bytes = try encryptedBytes(plain)
        let tampered = flip(bytes, at: bytes.count - 1) // last byte of the (only) chunk's tag
        assertAuthFailure(VaultCryptoTestSupport.writeTempFile(tampered))
    }

    func testTamperingTheHeadersTotalLengthFieldInvalidatesEveryRemainingChunk() throws {
        // This is the core claim behind binding header fields into every chunk's
        // AAD (VaultFormat.chunkAad): editing totalPlaintextLength changes the
        // recomputed AAD for ALL chunks, not just chunks "after" the edit, so
        // even chunk 0 - untouched on disk - fails to authenticate.
        let plain = VaultCryptoTestSupport.randomData(chunkSize * 3)
        let bytes = try encryptedBytes(plain)
        // total-length field is the last 8 bytes of the header.
        let lengthFieldStart = VaultFormat.headerSizeBytes - 8
        let tampered = flip(bytes, at: lengthFieldStart + 7) // shrink by 1
        assertAuthFailure(VaultCryptoTestSupport.writeTempFile(tampered))
    }

    func testReorderingTwoChunksIsDetectedViaTheChunkIndexBinding() throws {
        let plain = VaultCryptoTestSupport.randomData(chunkSize * 2)
        let bytes = try encryptedBytes(plain)

        let storedChunkLen = chunkSize + VaultFormat.tagSizeBytes
        let chunk0Start = VaultFormat.headerSizeBytes
        let chunk1Start = chunk0Start + storedChunkLen

        var swapped = bytes
        let chunk0 = bytes.subdata(in: chunk0Start..<(chunk0Start + storedChunkLen))
        let chunk1 = bytes.subdata(in: chunk1Start..<(chunk1Start + storedChunkLen))
        swapped.replaceSubrange(chunk0Start..<(chunk0Start + storedChunkLen), with: chunk1)
        swapped.replaceSubrange(chunk1Start..<(chunk1Start + storedChunkLen), with: chunk0)

        assertAuthFailure(VaultCryptoTestSupport.writeTempFile(swapped))
    }

    func testSplicingInAChunkFromADifferentFileIsDetectedViaTheFileIdBinding() throws {
        let plainA = VaultCryptoTestSupport.randomData(chunkSize)
        let otherFileId = VaultCryptoTestSupport.randomData(16)
        let plainB = VaultCryptoTestSupport.randomData(chunkSize)

        let bytesB = try VaultCryptoTestSupport.encryptedBytes(vmk: vmk, fileId: otherFileId, plain: plainB, chunkSize: chunkSize)
        let chunkFromB = bytesB.subdata(in: VaultFormat.headerSizeBytes..<bytesB.count)

        var bytesA = try encryptedBytes(plainA)
        bytesA.replaceSubrange(VaultFormat.headerSizeBytes..<(VaultFormat.headerSizeBytes + chunkFromB.count), with: chunkFromB)

        assertAuthFailure(VaultCryptoTestSupport.writeTempFile(bytesA))
    }

    func testTruncatingTrailingBytesMidChunkIsDetectedAsAShortRead() throws {
        let plain = VaultCryptoTestSupport.randomData(chunkSize + 20)
        let bytes = try encryptedBytes(plain)
        let truncated = bytes.prefix(bytes.count - 5) // chop off part of the last chunk's tag
        // Header still claims the original (larger) length, so the reader will
        // try to read a chunk that no longer has enough bytes on disk.
        assertTruncated(VaultCryptoTestSupport.writeTempFile(Data(truncated)))
    }

    func testTruncatingExactlyOnAChunkBoundaryIsStillDetected() throws {
        // Drop the entire final chunk. If an attacker leaves the header untouched,
        // the reader expects more chunks than physically exist -> short read.
        let plain = VaultCryptoTestSupport.randomData(chunkSize * 3)
        let bytes = try encryptedBytes(plain)
        let storedChunkLen = chunkSize + VaultFormat.tagSizeBytes
        let truncated = bytes.prefix(VaultFormat.headerSizeBytes + storedChunkLen * 2) // keep only 2 of 3 chunks
        assertTruncated(VaultCryptoTestSupport.writeTempFile(Data(truncated)))
    }

    func testAnEmptyFileStillGetsOneAuthenticatedChunkSoDeletingAllChunksIsStillDetected() throws {
        let bytes = try encryptedBytes(Data())
        XCTAssertNotEqual(VaultFormat.headerSizeBytes, bytes.count) // proves a chunk really was written

        let truncatedToHeaderOnly = bytes.prefix(VaultFormat.headerSizeBytes)
        assertTruncated(VaultCryptoTestSupport.writeTempFile(Data(truncatedToHeaderOnly)))
    }
}
