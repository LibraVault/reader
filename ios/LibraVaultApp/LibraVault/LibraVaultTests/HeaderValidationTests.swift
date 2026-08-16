import XCTest
@testable import LibraVault

/// Regression tests for the same two issues Android's `HeaderValidationTest.kt`
/// guards against: a corrupted `chunkSize == 0` header field failing cleanly
/// instead of crashing on an unguarded division, and `VaultFormat.chunkAad`
/// actually using its `formatVersion`/`cipherId` parameters rather than
/// silently defaulting to the current build's constants.
final class HeaderValidationTests: XCTestCase {

    private let vmk = VaultCryptoTestSupport.randomData(32)
    private let fileId = VaultCryptoTestSupport.randomData(16)

    private func validEncryptedBytes() throws -> Data {
        let plain = VaultCryptoTestSupport.randomData(10)
        return try VaultCryptoTestSupport.encryptedBytes(vmk: vmk, fileId: fileId, plain: plain, chunkSize: 64)
    }

    /// Overwrites the 4-byte chunkSize field (offset 18, per headerSizeBytes layout: 1+1+16).
    private func withChunkSize(_ bytes: Data, value: Int32) -> Data {
        var out = bytes
        let offset = 2 + VaultFormat.fileIdSizeBytes
        let be = BigEndian.bytes(ofUInt32: UInt32(bitPattern: value))
        for (i, byte) in be.enumerated() {
            out[out.index(out.startIndex, offsetBy: offset + i)] = byte
        }
        return out
    }

    private func assertMalformedHeader(_ url: URL) {
        defer { try? FileManager.default.removeItem(at: url) }
        XCTAssertThrowsError(try VaultFileReader(fileURL: url, vmk: vmk, expectedFileId: fileId)) { error in
            guard case .malformedHeader = error as? VaultCryptoError else {
                XCTFail("expected .malformedHeader, got \(error)")
                return
            }
        }
    }

    func testChunkSizeOfZeroInTheHeaderFailsCleanlyNotWithAnUnhandledException() throws {
        let corrupted = withChunkSize(try validEncryptedBytes(), value: 0)
        assertMalformedHeader(VaultCryptoTestSupport.writeTempFile(corrupted))
    }

    func testNegativeChunkSizeInTheHeaderFailsCleanly() throws {
        let corrupted = withChunkSize(try validEncryptedBytes(), value: -1)
        assertMalformedHeader(VaultCryptoTestSupport.writeTempFile(corrupted))
    }

    func testAbsurdlyLargeChunkSizeInTheHeaderIsRejectedRatherThanAttemptingAHugeAllocation() throws {
        let corrupted = withChunkSize(try validEncryptedBytes(), value: Int32.max)
        assertMalformedHeader(VaultCryptoTestSupport.writeTempFile(corrupted))
    }

    func testChunkAadOutputDependsOnTheFormatVersionAndCipherIdParametersNotJustBuildConstants() {
        // Proves the AAD builder actually uses its parameters (the bug being
        // guarded against: hardcoding VaultFormat.formatVersion/cipherAes256Gcm
        // internally instead of taking them as arguments).
        let fid = VaultCryptoTestSupport.randomData(16)
        let aadV1 = VaultFormat.chunkAad(formatVersion: 1, cipherId: 1, fileId: fid, totalPlaintextLength: 100, chunkSize: 1024, chunkIndex: 0, isFinalChunk: true)
        let aadV2 = VaultFormat.chunkAad(formatVersion: 2, cipherId: 1, fileId: fid, totalPlaintextLength: 100, chunkSize: 1024, chunkIndex: 0, isFinalChunk: true)
        let aadCipher2 = VaultFormat.chunkAad(formatVersion: 1, cipherId: 2, fileId: fid, totalPlaintextLength: 100, chunkSize: 1024, chunkIndex: 0, isFinalChunk: true)
        XCTAssertNotEqual(aadV1, aadV2)
        XCTAssertNotEqual(aadV1, aadCipher2)
    }
}
