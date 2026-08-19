import XCTest
@testable import LibraVault

final class LibraryFileScannerTests: XCTestCase {
    private var tempDir: URL!

    override func setUpWithError() throws {
        tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("LibraryFileScannerTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: tempDir)
    }

    private func write(_ name: String) throws {
        try Data().write(to: tempDir.appendingPathComponent(name))
    }

    private var folder: Folder {
        Folder(id: "v1", displayName: "Test Folder", bookmarkData: Data())
    }

    func testScanFindsKnownBookAndAudiobookExtensions() throws {
        try write("Gatsby.epub")
        try write("Report.PDF")
        try write("Chapter1.mp3")
        try write("Novel.m4b")
        try write("notes.txt")

        let results = LibraryFileScanner.scan(folder: folder, resolvedURL: tempDir)

        XCTAssertEqual(results.count, 4)
        XCTAssertTrue(results.contains { $0.title == "Gatsby" && $0.format == .epub })
        XCTAssertTrue(results.contains { $0.title == "Report" && $0.format == .pdf })
        XCTAssertTrue(results.contains { $0.title == "Chapter1" && $0.format == .mp3 })
        XCTAssertTrue(results.contains { $0.title == "Novel" && $0.format == .m4b })
    }

    func testScanFindsMarkdownFilesByBothExtensions() throws {
        try write("Notes.md")
        try write("LongForm.markdown")

        let results = LibraryFileScanner.scan(folder: folder, resolvedURL: tempDir)

        XCTAssertEqual(results.count, 2)
        XCTAssertTrue(results.contains { $0.title == "Notes" && $0.format == .markdown })
        XCTAssertTrue(results.contains { $0.title == "LongForm" && $0.format == .markdown })
        XCTAssertTrue(results.allSatisfy { !$0.format.isAudio })
    }

    func testScanIgnoresUnrecognizedExtensions() throws {
        try write("readme.txt")
        try write("cover.jpg")

        XCTAssertTrue(LibraryFileScanner.scan(folder: folder, resolvedURL: tempDir).isEmpty)
    }

    func testScanReturnsEmptyForEmptyDirectory() {
        XCTAssertTrue(LibraryFileScanner.scan(folder: folder, resolvedURL: tempDir).isEmpty)
    }

    func testScanFindsFilesInNestedSubdirectories() throws {
        let subdir = tempDir.appendingPathComponent("Series")
        try FileManager.default.createDirectory(at: subdir, withIntermediateDirectories: true)
        try Data().write(to: subdir.appendingPathComponent("Book1.epub"))

        let results = LibraryFileScanner.scan(folder: folder, resolvedURL: tempDir)

        XCTAssertEqual(results.count, 1)
        XCTAssertEqual(results.first?.title, "Book1")
    }

    func testScanIdsAreNamespacedByFolderId() throws {
        try write("Book.epub")
        let namedFolder = Folder(id: "folder-42", displayName: "Test", bookmarkData: Data())

        let results = LibraryFileScanner.scan(folder: namedFolder, resolvedURL: tempDir)

        XCTAssertEqual(results.count, 1)
        XCTAssertTrue(results[0].id.hasPrefix("folder:folder-42:"))
    }

    /// `fileURL`/`folderId` are what later reopen the real file for content parsing
    /// and playback — a scan that only produces filename/format metadata is useless
    /// for anything beyond the Library grid.
    func testScanPopulatesFileURLAndFolderId() throws {
        try write("Book.epub")
        let namedFolder = Folder(id: "folder-42", displayName: "Test", bookmarkData: Data())

        let results = LibraryFileScanner.scan(folder: namedFolder, resolvedURL: tempDir)

        let book = try XCTUnwrap(results.first)
        XCTAssertEqual(book.folderId, "folder-42")
        XCTAssertEqual(book.fileURL?.lastPathComponent, "Book.epub")
    }
}
