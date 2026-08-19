import XCTest
@testable import LibraVault

/// Tests for `VaultManifest` — issue #302.
///
/// Primarily regression coverage for the AES-GCM nonce-reuse fix this port
/// starts from (see `VaultManifest`'s type doc): every `write` used to
/// re-encrypt under the same fixed `legacyManifestFileId`, which — combined
/// with a VMK that never rotates — would reuse the exact same (key, nonce)
/// sequence on every write while encrypting different plaintext. Mirrors
/// Android's `VaultManifestTest.kt` test-by-test.
///
/// Scope note: id-assignment (distinct/increasing bookmark/highlight ids)
/// and "operating on an unknown fileId throws" belong to `VaultStore` (#304),
/// which owns the manifest's *entries*, not this type — `VaultManifest`
/// itself only serializes/encrypts/decrypts whatever `[VaultManifestEntry]`
/// it's given. Android's own `VaultManifestTest.kt` draws that same
/// boundary: those cases live in its `VaultStoreTest`/`VaultLeakClosureTest`,
/// not here.
final class VaultManifestTests: XCTestCase {

    private let vmk = VaultCryptoTestSupport.randomData(32)

    private func newVaultDir() -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("vaultmanifest-test-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private func entry(_ title: String) -> VaultManifestEntry {
        VaultManifestEntry(
            fileId: VaultCryptoTestSupport.randomData(16),
            title: title,
            author: "An Author",
            format: "EPUB",
            sizeBytes: 1234,
            addedAtEpochMillis: 0
        )
    }

    /// The 16-byte fileId embedded in the manifest blob's own
    /// unencrypted-but-authenticated header — see `VaultFormat.headerSizeBytes`'s
    /// layout (version + cipherId precede it).
    private func headerFileId(_ vaultDir: URL) throws -> Data {
        let bytes = try Data(contentsOf: VaultManifest.manifestPath(vaultDir: vaultDir))
        return bytes.subdata(in: 2..<(2 + VaultFormat.fileIdSizeBytes))
    }

    // MARK: - round trip

    func testWriteThenReadRoundTripsEntriesUnchanged() throws {
        let vaultDir = newVaultDir()
        let entries = [entry("Book One"), entry("Book Two")]

        try VaultManifest.write(vaultDir: vaultDir, vmk: vmk, entries: entries)
        let readBack = try VaultManifest.read(vaultDir: vaultDir, vmk: vmk)

        XCTAssertEqual(readBack, entries)
    }

    func testReadOnBrandNewVaultDirectoryReturnsEmptyList() throws {
        let vaultDir = newVaultDir()
        XCTAssertEqual(try VaultManifest.read(vaultDir: vaultDir, vmk: vmk), [])
    }

    func testEntryWithHighlightsAndBookmarksRoundTrips() throws {
        let vaultDir = newVaultDir()
        var withAnnotations = entry("Annotated Book")
        withAnnotations.coverArtFileId = VaultCryptoTestSupport.randomData(16)
        withAnnotations.highlights = [
            VaultHighlight(id: 1, positionRef: "epubcfi(/6/4)", highlightedText: "a passage", note: "why it matters", createdAtEpochMillis: 100),
            VaultHighlight(id: 2, positionRef: "epubcfi(/6/8)", highlightedText: "another passage", createdAtEpochMillis: 200),
        ]
        withAnnotations.bookmarks = [
            VaultBookmark(id: 1, positionRef: "page:12", label: "Chapter 2", createdAtEpochMillis: 150),
        ]

        try VaultManifest.write(vaultDir: vaultDir, vmk: vmk, entries: [withAnnotations])
        let readBack = try VaultManifest.read(vaultDir: vaultDir, vmk: vmk)

        XCTAssertEqual(readBack, [withAnnotations])
    }

    // MARK: - nonce-reuse regression

    func testTwoConsecutiveWritesEmbedTwoDifferentFileIdsInHeader() throws {
        // The actual regression test for the nonce-reuse bug: if the manifest
        // were still encrypted under one fixed fileId, this header field
        // would be identical across writes and every chunk's nonce would
        // repeat.
        let vaultDir = newVaultDir()

        try VaultManifest.write(vaultDir: vaultDir, vmk: vmk, entries: [entry("Book One")])
        let firstFileId = try headerFileId(vaultDir)

        try VaultManifest.write(vaultDir: vaultDir, vmk: vmk, entries: [entry("Book One"), entry("Book Two")])
        let secondFileId = try headerFileId(vaultDir)

        XCTAssertNotEqual(firstFileId, secondFileId, "manifest reused the same fileId (and therefore the same nonce sequence) across two writes")
        XCTAssertNotEqual(firstFileId, VaultManifest.legacyManifestFileId, "manifest wrote under the legacy all-zero sentinel instead of a fresh random id")
    }

    func testManyConsecutiveWritesNeverRepeatAFileId() throws {
        let vaultDir = newVaultDir()
        var seen = Set<Data>()

        for i in 0..<20 {
            try VaultManifest.write(vaultDir: vaultDir, vmk: vmk, entries: [entry("Book \(i)")])
            let id = try headerFileId(vaultDir)
            XCTAssertTrue(seen.insert(id).inserted, "fileId repeated after \(i) writes — nonce sequence would repeat too")
        }
    }

    // MARK: - legacy sentinel self-migration

    func testLegacyAllZeroFileIdManifestStillReadsBackCorrectlyAndSelfMigrates() throws {
        // Proves the fix is self-migrating: a manifest written by a pre-fix
        // build (there is no such build on iOS, but the logic mirrors
        // Android's, which does have one) must keep working with no
        // special-case migration code, and rotate off the sentinel on the
        // very next write.
        let vaultDir = newVaultDir()
        let entries = [entry("Legacy Book")]

        // Hand-assembled legacy JSON, deliberately missing the
        // "highlights"/"bookmarks" keys entirely (not just null) — proves
        // VaultManifestEntryDto's custom decoder defaults them to [] rather
        // than throwing keyNotFound, same as a manifest written before those
        // fields existed at all.
        let legacyJson = """
        {"entries":[{"fileIdHex":"\(entries[0].fileId.map { String(format: "%02x", $0) }.joined())",\
        "title":"\(entries[0].title)","author":"\(entries[0].author!)","format":"\(entries[0].format)",\
        "sizeBytes":\(entries[0].sizeBytes),"addedAtEpochMillis":\(entries[0].addedAtEpochMillis)}]}
        """
        let plainBytes = Data(legacyJson.utf8)

        let manifestURL = VaultManifest.manifestPath(vaultDir: vaultDir)
        FileManager.default.createFile(atPath: manifestURL.path, contents: nil)
        let input = InputStream(data: plainBytes)
        guard let output = OutputStream(url: manifestURL, append: false) else {
            XCTFail("could not open manifest file for writing")
            return
        }
        input.open()
        output.open()
        try ChunkedVaultWriter.encrypt(
            vmk: vmk, fileId: VaultManifest.legacyManifestFileId, totalPlaintextLength: Int64(plainBytes.count),
            input: input, output: output
        )
        input.close()
        output.close()

        let readBack = try VaultManifest.read(vaultDir: vaultDir, vmk: vmk)
        XCTAssertEqual(readBack, entries)

        // And the very next write must rotate it off the legacy sentinel.
        try VaultManifest.write(vaultDir: vaultDir, vmk: vmk, entries: entries)
        XCTAssertNotEqual(try headerFileId(vaultDir), VaultManifest.legacyManifestFileId)
    }
}
