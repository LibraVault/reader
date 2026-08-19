import XCTest
@testable import LibraVault

/// The behavioral half of #201's leak-closure design (the static half is
/// `VaultStoreHasNoLeakSurfaceDependencyTests`): proves no plaintext copy of
/// sensitive content exists anywhere in the vault directory on disk, not
/// just that the encrypted API round-trips correctly. Mirrors Android's
/// `VaultLeakClosureTest.kt` case-by-case.
final class VaultLeakClosureTests: XCTestCase {

    private let fastParams = Argon2Params(memoryKiB: 8 * 1024, iterations: 1, parallelism: 1)

    /// Tracks the backing directory alongside the store — needed by the
    /// plaintext-leak-scanning tests below, which have to inspect every file
    /// actually on disk, not just what the encrypted API reports.
    private var vaultDir: URL!

    private func newStore() -> VaultStore {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("vaultstore-leak-test-\(UUID().uuidString)")
        vaultDir = dir
        return VaultStore(vaultDir: dir, keystoreKeyAlias: "test-vault-alias", keyWrapFactory: FakeHardwareKeyWrapFactory())
    }

    private func pin(_ s: String) -> [UInt8] { Array(s.utf8) }

    private func emptyImport(_ store: VaultStore, title: String = "Title", coverArt: Data? = nil) throws -> VaultManifestEntry {
        let input = InputStream(data: Data(count: 10))
        input.open()
        defer { input.close() }
        return try store.importFile(input: input, declaredSize: 10, title: title, author: nil, format: "pdf", coverArt: coverArt)
    }

    // MARK: - Cover art

    func testCoverArtImportedAlongsideContentRoundTripsExactly() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        let cover = VaultCryptoTestSupport.randomData(2000)

        let entry = try emptyImport(store, coverArt: cover)

        XCTAssertNotNil(entry.coverArtFileId)
        XCTAssertEqual(try store.readCoverArt(fileId: entry.fileId), cover)
    }

    func testReadCoverArtReturnsNilWhenNoCoverWasSet() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        let entry = try emptyImport(store)
        XCTAssertNil(try store.readCoverArt(fileId: entry.fileId))
    }

    func testSetCoverArtAttachesACoverToAnAlreadyImportedFile() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        let entry = try emptyImport(store)

        let cover = VaultCryptoTestSupport.randomData(500)
        try store.setCoverArt(fileId: entry.fileId, jpegBytes: cover)

        XCTAssertEqual(try store.readCoverArt(fileId: entry.fileId), cover)
    }

    func testSetCoverArtReplacingAnExistingCoverRemovesTheOrphanedOldFile() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        let entry = try emptyImport(store, coverArt: VaultCryptoTestSupport.randomData(100))
        let firstCoverFileId = try store.listEntries()[0].coverArtFileId!

        let newCover = VaultCryptoTestSupport.randomData(200)
        try store.setCoverArt(fileId: entry.fileId, jpegBytes: newCover)

        XCTAssertFalse(FileManager.default.fileExists(atPath: store.contentFile(fileId: firstCoverFileId).path), "old cover file must not be left behind")
        XCTAssertEqual(try store.readCoverArt(fileId: entry.fileId), newCover)
    }

    func testOversizedCoverArtIsRejectedBeforeAnyBytesAreWritten() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        let tooBig = Data(count: VaultStore.maxCoverArtBytes + 1)

        XCTAssertThrowsError(try emptyImport(store, coverArt: tooBig)) { error in
            guard case .coverArtTooLarge = error as? VaultStoreError else {
                XCTFail("expected .coverArtTooLarge, got \(error)")
                return
            }
        }
        XCTAssertTrue(try store.listEntries().isEmpty, "a rejected import must not appear in the manifest")
    }

    func testCoverArtBytesNeverAppearInPlaintextAnywhereInTheVaultDirectory() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        // A distinctive, easy-to-search-for byte pattern rather than random noise.
        let cover = Data((0..<4096).map { UInt8($0 % 256) })
        let markerRun = cover.prefix(256) // a long enough run that random collision is not a concern

        _ = try emptyImport(store, coverArt: cover)

        try assertNoSubarrayOnDisk(vaultDir, Data(markerRun))
    }

    // MARK: - Highlights

    func testAHighlightRoundTripsThroughTheManifest() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        let entry = try emptyImport(store)

        let h = try store.addHighlight(fileId: entry.fileId, positionRef: "page:1:0,0,10,10", highlightedText: "a highlighted sentence", note: "my note")

        let reloaded = try store.listEntries().first { $0.fileId == entry.fileId }!
        XCTAssertEqual(reloaded.highlights, [h])
    }

    func testMultipleHighlightsGetDistinctIncreasingIds() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        let entry = try emptyImport(store)

        let h1 = try store.addHighlight(fileId: entry.fileId, positionRef: "ref1", highlightedText: "text1")
        let h2 = try store.addHighlight(fileId: entry.fileId, positionRef: "ref2", highlightedText: "text2")

        XCTAssertGreaterThan(h2.id, h1.id)
    }

    func testRemoveHighlightDeletesExactlyTheTargetedHighlight() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        let entry = try emptyImport(store)
        let h1 = try store.addHighlight(fileId: entry.fileId, positionRef: "ref1", highlightedText: "text1")
        let h2 = try store.addHighlight(fileId: entry.fileId, positionRef: "ref2", highlightedText: "text2")

        try store.removeHighlight(fileId: entry.fileId, highlightId: h1.id)

        XCTAssertEqual(try store.listEntries()[0].highlights, [h2])
    }

    func testRemovingANonexistentHighlightIdIsAHarmlessNoOp() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        let entry = try emptyImport(store)
        _ = try store.addHighlight(fileId: entry.fileId, positionRef: "ref1", highlightedText: "text1")

        try store.removeHighlight(fileId: entry.fileId, highlightId: 999) // does not throw
        XCTAssertEqual(try store.listEntries()[0].highlights.count, 1)
    }

    func testHighlightsSurviveALockUnlockCycle() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        let entry = try emptyImport(store)
        _ = try store.addHighlight(fileId: entry.fileId, positionRef: "ref", highlightedText: "some highlighted text")

        store.lock()
        _ = try store.unlockWithPin(pin("1234"))

        XCTAssertEqual(try store.listEntries()[0].highlights.count, 1)
    }

    func testAddHighlightAndRemoveHighlightOnAnUnknownFileIdThrowNotSilentlyNoOp() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        let bogusFileId = Data(count: 16)

        assertEntryNotFound(bogusFileId) { try store.addHighlight(fileId: bogusFileId, positionRef: "ref", highlightedText: "text") }
        assertEntryNotFound(bogusFileId) { try store.removeHighlight(fileId: bogusFileId, highlightId: 1) }
        assertEntryNotFound(bogusFileId) { try store.readCoverArt(fileId: bogusFileId) }
        assertEntryNotFound(bogusFileId) { try store.setCoverArt(fileId: bogusFileId, jpegBytes: Data(count: 10)) }
    }

    func testHighlightedTextNeverAppearsInPlaintextAnywhereInTheVaultDirectory() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        let entry = try emptyImport(store)

        let sensitiveText = "Patient John Q. Confidential has a rare condition XKCD1234"
        _ = try store.addHighlight(fileId: entry.fileId, positionRef: "ref", highlightedText: sensitiveText, note: "Client_Divorce_Case privileged note")

        try assertNoSubarrayOnDisk(vaultDir, Data(sensitiveText.utf8))
        try assertNoSubarrayOnDisk(vaultDir, Data("Client_Divorce_Case".utf8))
    }

    // MARK: - Bookmarks

    func testABookmarkRoundTripsThroughTheManifest() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        let entry = try emptyImport(store)

        let b = try store.addBookmark(fileId: entry.fileId, positionRef: "page:3", label: "Chapter 2", note: "my note")

        let reloaded = try store.listEntries().first { $0.fileId == entry.fileId }!
        XCTAssertEqual(reloaded.bookmarks, [b])
    }

    func testMultipleBookmarksGetDistinctIncreasingIds() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        let entry = try emptyImport(store)

        let b1 = try store.addBookmark(fileId: entry.fileId, positionRef: "page:1")
        let b2 = try store.addBookmark(fileId: entry.fileId, positionRef: "page:2")

        XCTAssertGreaterThan(b2.id, b1.id)
    }

    func testRemoveBookmarkDeletesExactlyTheTargetedBookmark() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        let entry = try emptyImport(store)
        let b1 = try store.addBookmark(fileId: entry.fileId, positionRef: "page:1")
        let b2 = try store.addBookmark(fileId: entry.fileId, positionRef: "page:2")

        try store.removeBookmark(fileId: entry.fileId, bookmarkId: b1.id)

        XCTAssertEqual(try store.listEntries()[0].bookmarks, [b2])
    }

    func testRemovingANonexistentBookmarkIdIsAHarmlessNoOp() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        let entry = try emptyImport(store)
        _ = try store.addBookmark(fileId: entry.fileId, positionRef: "page:1")

        try store.removeBookmark(fileId: entry.fileId, bookmarkId: 999) // does not throw
        XCTAssertEqual(try store.listEntries()[0].bookmarks.count, 1)
    }

    func testUpdateBookmarkNoteReplacesExactlyTheTargetedBookmarksNote() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        let entry = try emptyImport(store)
        let b1 = try store.addBookmark(fileId: entry.fileId, positionRef: "page:1", note: "old")
        let b2 = try store.addBookmark(fileId: entry.fileId, positionRef: "page:2", note: "unrelated")

        try store.updateBookmarkNote(fileId: entry.fileId, bookmarkId: b1.id, note: "new note")

        let reloaded = try store.listEntries()[0].bookmarks
        XCTAssertEqual(reloaded.first { $0.id == b1.id }?.note, "new note")
        XCTAssertEqual(reloaded.first { $0.id == b2.id }?.note, "unrelated")
    }

    func testBookmarksSurviveALockUnlockCycle() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        let entry = try emptyImport(store)
        _ = try store.addBookmark(fileId: entry.fileId, positionRef: "page:1", label: "a bookmark")

        store.lock()
        _ = try store.unlockWithPin(pin("1234"))

        XCTAssertEqual(try store.listEntries()[0].bookmarks.count, 1)
    }

    func testAddBookmarkRemoveBookmarkAndUpdateBookmarkNoteOnAnUnknownFileIdThrowNotSilentlyNoOp() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        let bogusFileId = Data(count: 16)

        assertEntryNotFound(bogusFileId) { try store.addBookmark(fileId: bogusFileId, positionRef: "ref") }
        assertEntryNotFound(bogusFileId) { try store.removeBookmark(fileId: bogusFileId, bookmarkId: 1) }
        assertEntryNotFound(bogusFileId) { try store.updateBookmarkNote(fileId: bogusFileId, bookmarkId: 1, note: "note") }
    }

    func testBookmarkLabelAndNoteNeverAppearInPlaintextAnywhereInTheVaultDirectory() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        let entry = try emptyImport(store)

        _ = try store.addBookmark(fileId: entry.fileId, positionRef: "page:1", label: "Deposition exhibit A", note: "Client_Divorce_Case privileged note")

        try assertNoSubarrayOnDisk(vaultDir, Data("Deposition exhibit A".utf8))
        try assertNoSubarrayOnDisk(vaultDir, Data("Client_Divorce_Case".utf8))
    }

    func testTitleAndAuthorNeverAppearInPlaintextAnywhereInTheVaultDirectory() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        let input = InputStream(data: Data(count: 10))
        input.open()
        _ = try store.importFile(input: input, declaredSize: 10, title: "Smith v. Jones Deposition Transcript", author: "Jane Attorney", format: "pdf")
        input.close()

        try assertNoSubarrayOnDisk(vaultDir, Data("Smith v. Jones".utf8))
        try assertNoSubarrayOnDisk(vaultDir, Data("Jane Attorney".utf8))
    }

    // MARK: - Helpers

    private func assertEntryNotFound<T>(
        _ fileId: Data, file: StaticString = #filePath, line: UInt = #line, _ block: () throws -> T
    ) {
        XCTAssertThrowsError(try block(), file: file, line: line) { error in
            XCTAssertEqual(error as? VaultStoreError, .entryNotFound(fileId: fileId), file: file, line: line)
        }
    }

    private func assertNoSubarrayOnDisk(_ dir: URL, _ needle: Data, file: StaticString = #filePath, line: UInt = #line) throws {
        let files = try FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)
        XCTAssertFalse(files.isEmpty, "expected at least one file on disk to actually check", file: file, line: line)
        for f in files {
            let bytes = try Data(contentsOf: f)
            XCTAssertFalse(bytes.containsSubarray(needle), "found plaintext bytes in \(f.lastPathComponent) — leak!", file: file, line: line)
        }
    }
}

private extension Data {
    /// Deliberately written as plain, unambiguous array slicing rather than
    /// a hand-rolled search-loop-with-early-exit — this file has no local
    /// Xcode to compile-check against, and a subtly wrong leak-detection
    /// helper (always/never returning `true`) would silently defeat every
    /// test in this file rather than fail loudly.
    func containsSubarray(_ needle: Data) -> Bool {
        guard !needle.isEmpty, needle.count <= count else { return false }
        let haystack = [UInt8](self)
        let pattern = [UInt8](needle)
        for start in 0...(haystack.count - pattern.count) {
            if Array(haystack[start..<(start + pattern.count)]) == pattern {
                return true
            }
        }
        return false
    }
}
