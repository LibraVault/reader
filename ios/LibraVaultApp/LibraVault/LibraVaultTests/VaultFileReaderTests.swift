import XCTest
@testable import LibraVault

/// Covers `VaultFileReader.close()`'s key-scrubbing behavior — the Swift
/// port of the Kotlin fix from #525/#526 (PR #539's `VaultFileReader.close()`
/// change). Round-trip correctness itself is `ChunkedVaultRoundTripTests`'
/// job; this file is only about what's left in memory after `close()`.
final class VaultFileReaderTests: XCTestCase {

    private let vmk = VaultCryptoTestSupport.randomData(32)
    private let fileId = VaultCryptoTestSupport.randomData(16)
    private let smallChunkSize = 64

    /// Regression test for the gap `#550`/`#526`'s reopening surfaced:
    /// closing only the file handle left a live, unscrubbed copy of the
    /// derived `fileContentKey` (and the last-decrypted plaintext chunk)
    /// sitting in memory for as long as the `VaultFileReader` instance
    /// itself lived — exploitable the moment a caller holds a reader open
    /// across a screen's lifetime rather than draining-and-closing within
    /// one call, which is exactly the shape a future lazy/streaming reader
    /// (flagged as a future path on `VaultStore.readFullContent`'s own doc
    /// comment) would take.
    func testCloseZeroesFileContentKeyAndCachedChunk() throws {
        let plain = VaultCryptoTestSupport.randomData(smallChunkSize + 10)
        let url = try VaultCryptoTestSupport.encryptToTempFile(vmk: vmk, fileId: fileId, plain: plain, chunkSize: smallChunkSize)
        defer { try? FileManager.default.removeItem(at: url) }

        let reader = try VaultFileReader(fileURL: url, vmk: vmk, expectedFileId: fileId)
        _ = try reader.readAt(offset: 0, length: 5) // populate cachedChunk
        XCTAssertNotEqual(reader.fileContentKey, Data(count: reader.fileContentKey.count), "precondition: key must be non-zero before close()")
        XCTAssertNotNil(reader.cachedChunk)

        reader.close()

        XCTAssertEqual(reader.fileContentKey, Data(count: reader.fileContentKey.count), "close() must zero fileContentKey")
        XCTAssertNil(reader.cachedChunk, "close() must drop the cached plaintext chunk")
    }

    /// `close()` is documented as idempotent (matches `VaultStore.lock()`'s
    /// own idempotency) — `deinit` always calls it once more on top of
    /// whatever the caller already did, so a second call zeroing already-
    /// zero `Data` must not crash or misbehave.
    func testCloseIsIdempotent() throws {
        let plain = VaultCryptoTestSupport.randomData(10)
        let url = try VaultCryptoTestSupport.encryptToTempFile(vmk: vmk, fileId: fileId, plain: plain, chunkSize: smallChunkSize)
        defer { try? FileManager.default.removeItem(at: url) }

        let reader = try VaultFileReader(fileURL: url, vmk: vmk, expectedFileId: fileId)
        reader.close()
        reader.close() // must not crash

        XCTAssertEqual(reader.fileContentKey, Data(count: reader.fileContentKey.count))
    }
}
