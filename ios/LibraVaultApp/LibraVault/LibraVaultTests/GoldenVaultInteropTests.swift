import XCTest
@testable import LibraVault

private enum VaultInteropTestError: Error {
    case repoRootNotFound
}

/// Pins the iOS vault implementation against the same frozen artifact Android
/// is pinned against: `testdata/vault-format/v1/golden.vault`.
///
/// This is the test that makes Android/iOS vault interop a checked property
/// rather than an assumption. `core:vaultcrypto` and `Sources/VaultCrypto` are
/// two independent implementations of one on-disk format. Every other test on
/// both sides is a round-trip against itself, and a round-trip cannot catch the
/// failure that matters: a wrong-but-deterministic implementation round-trips
/// perfectly and still cannot open the other platform's vaults. Before this
/// existed, `VaultCryptoTestSupport.swift` mirrored the *shape* of Android's
/// tests, which lets both sides drift together undetected.
///
/// Because the format is fully deterministic — derived nonces, no random IV or
/// per-file salt in the blob — encrypting the fixed inputs below must produce
/// byte-identical output on every platform for format version 1. So this
/// asserts the bytes, not just that a round-trip succeeds.
///
/// One assertion transitively pins HKDF (which has no known-answer test of its
/// own on either platform), the AAD layout, the header layout, the chunk-count
/// rule and the final-chunk flag: change any of them and the ciphertext moves.
///
/// The fixture is generated from the **Android** writer, deliberately — one
/// generator, one source of truth. iOS only ever verifies. See
/// `GoldenVaultInteropTest.kt` for the regeneration procedure and for what to
/// do when this fails (short version: if you meant to change the format, bump
/// the version and add a new fixture; if you did not, you have just broken
/// every existing vault).
final class GoldenVaultInteropTests: XCTestCase {

    // Fixed test inputs — identical to GoldenVaultInteropTest.kt. Not secrets;
    // the point is that they are public, boring and reproducible.
    private let vmk = Data((0..<32).map { UInt8($0) })
    private let fileId = Data((0..<16).map { UInt8(0xA0 + $0) })

    /// Small on purpose: 150 bytes over a 64-byte chunk gives 64 + 64 + 22, so
    /// the fixture covers multi-chunk nonce derivation and a partial final
    /// chunk rather than a single-chunk happy path.
    private let chunkSize = 64

    /// Defined by formula so both languages reproduce it exactly with no
    /// encoding questions: plaintext[i] = (i * 7 + 11) % 251
    private let plaintext = Data((0..<150).map { UInt8(($0 * 7 + 11) % 251) })

    /// Resolved from the test source location rather than the test bundle: the
    /// fixture is shared with Android and lives at the repo root, so it is not
    /// a bundle resource on either side. Tests always run on a machine that has
    /// the checkout, so `#filePath` is well-defined here.
    /// Note this asserts rather than skips. A test that cannot find its fixture
    /// must go red, not green-by-skipping — a silently skipped interop test is
    /// indistinguishable from a passing one, which is the whole failure mode
    /// this class exists to prevent.
    private func repoRoot() throws -> URL {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()   // LibraVaultTests
            .deletingLastPathComponent()   // LibraVault
            .deletingLastPathComponent()   // LibraVaultApp
            .deletingLastPathComponent()   // ios
            .deletingLastPathComponent()   // repo root
        let marker = url.appendingPathComponent("settings.gradle.kts")
        guard FileManager.default.fileExists(atPath: marker.path) else {
            XCTFail(
                "Could not locate the repo root from \(#filePath) (looked for \(marker.path)). "
                    + "If the iOS project moved, fix repoRoot()."
            )
            throw VaultInteropTestError.repoRootNotFound
        }
        return url
    }

    private func fixtureURL() throws -> URL {
        let url = try repoRoot().appendingPathComponent("testdata/vault-format/v1/golden.vault")
        XCTAssertTrue(
            FileManager.default.fileExists(atPath: url.path),
            "Golden fixture missing at \(url.path). It is committed to the repo — see "
                + "GoldenVaultInteropTest.kt before regenerating it."
        )
        return url
    }

    // MARK: - Writer side

    func testWriterReproducesTheGoldenVaultByteForByte() throws {
        let expected = try Data(contentsOf: try fixtureURL())

        let output = OutputStream.toMemory()
        output.open()
        defer { output.close() }
        let input = InputStream(data: plaintext)
        input.open()
        defer { input.close() }

        try ChunkedVaultWriter.encrypt(
            vmk: vmk,
            fileId: fileId,
            totalPlaintextLength: Int64(plaintext.count),
            input: input,
            output: output,
            chunkSize: chunkSize
        )

        let produced = output.property(forKey: .dataWrittenToMemoryStreamKey) as? Data ?? Data()

        XCTAssertEqual(
            produced, expected,
            "The iOS writer no longer reproduces the committed v1 fixture that the Android writer "
                + "produced. The two implementations have diverged, or the format changed."
        )
    }

    // MARK: - Reader side

    func testReaderRecoversTheExactPlaintextFromTheGoldenVault() throws {
        let reader = try VaultFileReader(fileURL: try fixtureURL(), vmk: vmk, expectedFileId: fileId)
        defer { reader.close() }

        XCTAssertEqual(reader.plainSize, Int64(plaintext.count), "plaintext length")
        XCTAssertEqual(reader.chunkSize, chunkSize, "chunk size")
        XCTAssertEqual(reader.fileId, fileId, "file id")
        XCTAssertEqual(
            try reader.readAt(offset: 0, length: plaintext.count), plaintext,
            "iOS could not decrypt a vault file written by Android."
        )
    }

    /// Reads that straddle a chunk boundary and that land inside the short
    /// final chunk. A reader mishandling partial chunks can still pass a
    /// whole-file read from offset 0 by concatenating correctly.
    func testReaderHandlesReadsSpanningChunkBoundaries() throws {
        let reader = try VaultFileReader(fileURL: try fixtureURL(), vmk: vmk, expectedFileId: fileId)
        defer { reader.close() }

        // 50..<110 straddles the 64-byte boundary between chunk 0 and chunk 1.
        XCTAssertEqual(
            try reader.readAt(offset: 50, length: 60),
            plaintext.subdata(in: 50..<110),
            "cross-boundary read"
        )

        // 130..<150 lands inside the 22-byte final chunk.
        XCTAssertEqual(
            try reader.readAt(offset: 130, length: 20),
            plaintext.subdata(in: 130..<150),
            "final short chunk read"
        )
    }

    // MARK: - Header layout

    /// Asserts the frozen v1 header explicitly so a header-layout change gives
    /// a precise failure rather than only an opaque whole-file mismatch.
    func testGoldenVaultHeaderMatchesFrozenV1Layout() throws {
        let bytes = try Data(contentsOf: try fixtureURL())

        XCTAssertEqual(bytes[0], VaultFormat.formatVersion, "byte 0 = format version")
        XCTAssertEqual(bytes[1], VaultFormat.cipherAes256Gcm, "byte 1 = cipher id")
        XCTAssertEqual(bytes.subdata(in: 2..<18), fileId, "bytes 2..17 = file id")

        let storedChunkSize = bytes.subdata(in: 18..<22).reduce(Int(0)) { ($0 << 8) | Int($1) }
        XCTAssertEqual(storedChunkSize, chunkSize, "bytes 18..21 = chunk size (big-endian)")

        let storedLength = bytes.subdata(in: 22..<30).reduce(Int64(0)) { ($0 << 8) | Int64($1) }
        XCTAssertEqual(storedLength, Int64(plaintext.count), "bytes 22..29 = total plaintext length")

        // 3 chunks: two full (64) + one partial (22), each carrying a 16-byte tag.
        XCTAssertEqual(
            bytes.count,
            VaultFormat.headerSizeBytes + plaintext.count + 3 * VaultFormat.tagSizeBytes,
            "total file size"
        )
    }
}
