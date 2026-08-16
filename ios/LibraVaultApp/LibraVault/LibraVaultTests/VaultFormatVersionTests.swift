import XCTest
@testable import LibraVault

/// Proves an unrecognized format version or cipher id is rejected cleanly and
/// immediately, not misinterpreted or allowed to crash unpredictably deeper
/// in the code. Mirrors Android's `VaultFormatVersionTest.kt`.
final class VaultFormatVersionTests: XCTestCase {

    private let vmk = VaultCryptoTestSupport.randomData(32)
    private let fileId = VaultCryptoTestSupport.randomData(16)

    private func validEncryptedBytes() throws -> Data {
        let plain = VaultCryptoTestSupport.randomData(10)
        return try VaultCryptoTestSupport.encryptedBytes(vmk: vmk, fileId: fileId, plain: plain, chunkSize: 64)
    }

    func testAFutureUnrecognizedFormatVersionIsRejectedCleanly() throws {
        var bytes = try validEncryptedBytes()
        bytes[bytes.startIndex] = 99 // format version byte

        let url = VaultCryptoTestSupport.writeTempFile(bytes)
        defer { try? FileManager.default.removeItem(at: url) }
        XCTAssertThrowsError(try VaultFileReader(fileURL: url, vmk: vmk, expectedFileId: fileId)) { error in
            XCTAssertEqual(.unsupportedFormatVersion(found: 99), error as? VaultCryptoError)
        }
    }

    func testAnUnrecognizedCipherIdIsRejectedCleanly() throws {
        var bytes = try validEncryptedBytes()
        bytes[bytes.index(after: bytes.startIndex)] = 99 // cipher id byte

        let url = VaultCryptoTestSupport.writeTempFile(bytes)
        defer { try? FileManager.default.removeItem(at: url) }
        XCTAssertThrowsError(try VaultFileReader(fileURL: url, vmk: vmk, expectedFileId: fileId)) { error in
            XCTAssertEqual(.unsupportedCipher(found: 99), error as? VaultCryptoError)
        }
    }

    func testAFileShorterThanTheHeaderIsRejectedAsTruncatedNotCrashedOn() {
        let url = VaultCryptoTestSupport.writeTempFile(Data(count: 5)) // far shorter than headerSizeBytes
        defer { try? FileManager.default.removeItem(at: url) }
        XCTAssertThrowsError(try VaultFileReader(fileURL: url, vmk: vmk, expectedFileId: fileId)) { error in
            guard case .truncated = error as? VaultCryptoError else {
                XCTFail("expected .truncated, got \(error)")
                return
            }
        }
    }

    func testMismatchedFileIdIsRejectedBeforeTouchingChunkContent() throws {
        let bytes = try validEncryptedBytes()
        let url = VaultCryptoTestSupport.writeTempFile(bytes)
        defer { try? FileManager.default.removeItem(at: url) }
        let wrongFileId = VaultCryptoTestSupport.randomData(16)
        XCTAssertThrowsError(try VaultFileReader(fileURL: url, vmk: vmk, expectedFileId: wrongFileId)) { error in
            XCTAssertEqual(.authenticationFailed, error as? VaultCryptoError)
        }
    }
}
