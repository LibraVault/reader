import Foundation
import Security
@testable import LibraVault

/// Shared helpers for the VaultCrypto*Tests files - centralizes what Android's
/// equivalent test classes each duplicated as a private per-class helper
/// (`encryptedBytes`/`toTempFile`), since Swift/XCTest doesn't have JUnit's
/// per-class-instance convenience for that and duplicating it six times would
/// just be noise. Not compiled into the app target - lives only in LibraVaultTests.
enum VaultCryptoTestSupport {

    static func randomData(_ count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        _ = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        return Data(bytes)
    }

    /// Encrypts `plain` into a fresh temp file using `ChunkedVaultWriter`, returning its URL.
    /// Caller is responsible for removing it (most tests just let `tearDown`/OS temp-dir
    /// cleanup handle it, matching the Kotlin tests' `deleteOnExit`/manual `.delete()` mix).
    static func encryptToTempFile(
        vmk: Data,
        fileId: Data,
        plain: Data,
        chunkSize: Int,
        prefix: String = "vaultcrypto-test"
    ) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(prefix)-\(UUID().uuidString)")
        FileManager.default.createFile(atPath: url.path, contents: nil)

        let input = InputStream(data: plain)
        guard let output = OutputStream(url: url, append: false) else {
            throw VaultCryptoError.ioError("Could not open temp file for writing: \(url)")
        }
        input.open()
        output.open()
        defer {
            input.close()
            output.close()
        }
        try ChunkedVaultWriter.encrypt(
            vmk: vmk,
            fileId: fileId,
            totalPlaintextLength: Int64(plain.count),
            input: input,
            output: output,
            chunkSize: chunkSize
        )
        return url
    }

    /// Raw encrypted bytes (header + every chunk) for a plaintext, without leaving a temp file behind.
    static func encryptedBytes(vmk: Data, fileId: Data, plain: Data, chunkSize: Int) throws -> Data {
        let url = try encryptToTempFile(vmk: vmk, fileId: fileId, plain: plain, chunkSize: chunkSize, prefix: "vaultcrypto-bytes")
        defer { try? FileManager.default.removeItem(at: url) }
        return try Data(contentsOf: url)
    }

    /// Writes raw bytes (e.g. tampered ciphertext assembled by hand) to a fresh temp file.
    static func writeTempFile(_ bytes: Data, prefix: String = "vaultcrypto-test") -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(prefix)-\(UUID().uuidString)")
        FileManager.default.createFile(atPath: url.path, contents: bytes)
        return url
    }
}
