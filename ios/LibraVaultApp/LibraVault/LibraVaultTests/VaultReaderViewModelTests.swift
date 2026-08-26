import XCTest
import UIKit
@testable import LibraVault

@MainActor
final class VaultReaderViewModelTests: XCTestCase {

    private func makeUnlockedVault() async throws -> (manager: VaultSessionManager, id: String) {
        let rootDir = FileManager.default.temporaryDirectory.appendingPathComponent("reader-vm-test-\(UUID().uuidString)")
        let manager = VaultSessionManager(rootDir: rootDir, keyWrapFactory: FakeHardwareKeyWrapFactory())
        let result = try await manager.createVault(displayName: "Personal", pin: Array("1234".utf8))
        guard case .success(let id, _) = result else { XCTFail("expected .success"); return (manager, "") }
        return (manager, id)
    }

    private func importFixtureEPUB(into manager: VaultSessionManager, vaultId: String, chapterBodies: [String]) async throws -> Data {
        let sourceDir = FileManager.default.temporaryDirectory.appendingPathComponent("reader-vm-epub-\(UUID().uuidString)")
        let oebpsDir = sourceDir.appendingPathComponent("OEBPS", isDirectory: true)
        let metaInfDir = sourceDir.appendingPathComponent("META-INF", isDirectory: true)
        try FileManager.default.createDirectory(at: oebpsDir, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: metaInfDir, withIntermediateDirectories: true)
        try "application/epub+zip".write(to: sourceDir.appendingPathComponent("mimetype"), atomically: true, encoding: .utf8)
        try """
        <?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>
        """.write(to: metaInfDir.appendingPathComponent("container.xml"), atomically: true, encoding: .utf8)
        let manifestItems = chapterBodies.indices.map { "<item id=\"c\($0)\" href=\"c\($0).xhtml\" media-type=\"application/xhtml+xml\"/>" }.joined()
        let spineItems = chapterBodies.indices.map { "<itemref idref=\"c\($0)\"/>" }.joined()
        try """
        <?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <manifest>\(manifestItems)</manifest><spine>\(spineItems)</spine>
        </package>
        """.write(to: oebpsDir.appendingPathComponent("content.opf"), atomically: true, encoding: .utf8)
        for (index, body) in chapterBodies.enumerated() {
            try "<html><body>\(body)</body></html>".write(to: oebpsDir.appendingPathComponent("c\(index).xhtml"), atomically: true, encoding: .utf8)
        }
        let epubURL = FileManager.default.temporaryDirectory.appendingPathComponent("reader-vm-fixture-\(UUID().uuidString).epub")
        try FileManager().zipItem(at: sourceDir, to: epubURL, shouldKeepParent: false)
        let data = try Data(contentsOf: epubURL)

        let store = await manager.requireUnlocked(vaultId)
        let input = InputStream(data: data)
        input.open()
        let entry = try store.importFile(input: input, declaredSize: Int64(data.count), title: "My Book", author: nil, format: "epub")
        input.close()
        return entry.fileId
    }

    /// A real, valid, multi-page PDF (drawn via `UIGraphicsPDFRenderer`, real
    /// text-showing operators, not an image) — `PDFDocument(data:)` and
    /// `PDFPage.string` both parse it exactly like a real imported document.
    private func makeFixturePDFData(pageCount: Int) -> Data {
        let pageRect = CGRect(x: 0, y: 0, width: 200, height: 200)
        let renderer = UIGraphicsPDFRenderer(bounds: pageRect)
        return renderer.pdfData { context in
            for index in 0..<pageCount {
                context.beginPage()
                let text = "Page \(index + 1) content" as NSString
                text.draw(at: CGPoint(x: 10, y: 10), withAttributes: [.font: UIFont.systemFont(ofSize: 18)])
            }
        }
    }

    private func importFixturePDF(into manager: VaultSessionManager, vaultId: String, pageCount: Int) async throws -> Data {
        let pdfData = makeFixturePDFData(pageCount: pageCount)
        let store = await manager.requireUnlocked(vaultId)
        let input = InputStream(data: pdfData)
        input.open()
        let entry = try store.importFile(input: input, declaredSize: Int64(pdfData.count), title: "My PDF", author: nil, format: "pdf")
        input.close()
        return entry.fileId
    }

    private func importFixtureMarkdown(into manager: VaultSessionManager, vaultId: String, text: String) async throws -> Data {
        let data = Data(text.utf8)
        let store = await manager.requireUnlocked(vaultId)
        let input = InputStream(data: data)
        input.open()
        let entry = try store.importFile(input: input, declaredSize: Int64(data.count), title: "My Notes", author: nil, format: "markdown")
        input.close()
        return entry.fileId
    }

    /// Regression coverage for the QA gap this file originally shipped
    /// with: nothing exercised `VaultReaderViewModel.load()`'s
    /// `PDFDocument(data:)` success branch through to `.pdfReady` — every
    /// other `format: "pdf"` reference in this PR's tests was either
    /// manifest-only plumbing or an audio-player rejection case.
    func testLoadPdfReadyExposesTheDecryptedDocument() async throws {
        let (manager, id) = try await makeUnlockedVault()
        let fileId = try await importFixturePDF(into: manager, vaultId: id, pageCount: 3)

        let vm = VaultReaderViewModel(vaultId: id, fileId: fileId, sessionManager: manager)
        await vm.load()

        XCTAssertEqual(vm.state, .pdfReady(title: "My PDF"))
        XCTAssertEqual(vm.pdfDocument?.pageCount, 3)
    }

    func testAddHighlightOnAPdfCapturesTheCurrentPageTextAndPersists() async throws {
        let (manager, id) = try await makeUnlockedVault()
        let fileId = try await importFixturePDF(into: manager, vaultId: id, pageCount: 2)
        let vm = VaultReaderViewModel(vaultId: id, fileId: fileId, sessionManager: manager)
        await vm.load()
        vm.currentPageIndex = 1

        await vm.addHighlight()

        XCTAssertEqual(vm.highlights.count, 1)
        XCTAssertEqual(vm.highlights.first?.positionRef, "page:1")
        XCTAssertTrue(vm.highlights.first?.highlightedText.contains("Page 2 content") ?? false)

        let vm2 = VaultReaderViewModel(vaultId: id, fileId: fileId, sessionManager: manager)
        await vm2.load()
        XCTAssertEqual(vm2.highlights.count, 1)
    }

    func testLoadEpubReadyExposesChaptersFromTheDecryptedContent() async throws {
        let (manager, id) = try await makeUnlockedVault()
        let fileId = try await importFixtureEPUB(into: manager, vaultId: id, chapterBodies: [
            "<p>First chapter text.</p>",
            "<p>Second chapter text.</p>",
        ])

        let vm = VaultReaderViewModel(vaultId: id, fileId: fileId, sessionManager: manager)
        await vm.load()

        XCTAssertEqual(vm.state, .epubReady(title: "My Book"))
        XCTAssertEqual(vm.chapters.count, 2)
        XCTAssertTrue(vm.chapters[0].text.contains("First chapter text."))
    }

    func testLoadOnALockedVaultReportsAnError() async throws {
        let (manager, id) = try await makeUnlockedVault()
        let fileId = try await importFixtureEPUB(into: manager, vaultId: id, chapterBodies: ["<p>Text.</p>"])
        await manager.lock(id)

        let vm = VaultReaderViewModel(vaultId: id, fileId: fileId, sessionManager: manager)
        await vm.load()

        XCTAssertEqual(vm.state, .error("Vault is locked"))
    }

    func testLoadForAnAudioEntryReportsWrongScreen() async throws {
        let (manager, id) = try await makeUnlockedVault()
        let store = await manager.requireUnlocked(id)
        let audioBytes = VaultCryptoTestSupport.randomData(1_000)
        let input = InputStream(data: audioBytes)
        input.open()
        let entry = try store.importFile(input: input, declaredSize: Int64(audioBytes.count), title: "A Track", author: nil, format: "mp3")
        input.close()

        let vm = VaultReaderViewModel(vaultId: id, fileId: entry.fileId, sessionManager: manager)
        await vm.load()

        guard case .wrongScreen = vm.state else {
            XCTFail("expected .wrongScreen, got \(vm.state)")
            return
        }
    }

    /// #442 — a "markdown" entry used to fall through to the `default` branch
    /// and surface "Unsupported format: markdown" instead of opening.
    func testLoadMarkdownReadyExposesTheDecodedTextAndParsedBlocks() async throws {
        let (manager, id) = try await makeUnlockedVault()
        let markdownText = "# Heading\n\nSome body text."
        let fileId = try await importFixtureMarkdown(into: manager, vaultId: id, text: markdownText)

        let vm = VaultReaderViewModel(vaultId: id, fileId: fileId, sessionManager: manager)
        await vm.load()

        XCTAssertEqual(vm.state, .markdownReady(title: "My Notes", text: markdownText))
        XCTAssertEqual(vm.markdownBlocks.count, 2)
    }

    /// Round-trips a bookmark through the encrypted manifest — #203's
    /// acceptance criterion — by reading it back from a *second*, freshly
    /// constructed view model against the same vault, not just the same
    /// in-memory instance that created it.
    func testAddBookmarkPersistsAndRoundTripsAcrossANewViewModelInstance() async throws {
        let (manager, id) = try await makeUnlockedVault()
        let fileId = try await importFixtureEPUB(into: manager, vaultId: id, chapterBodies: ["<p>One.</p>", "<p>Two.</p>"])

        let vm = VaultReaderViewModel(vaultId: id, fileId: fileId, sessionManager: manager)
        await vm.load()
        vm.currentChapterIndex = 1
        await vm.addBookmark(label: "Nice bit")

        XCTAssertEqual(vm.bookmarks.count, 1)
        XCTAssertEqual(vm.bookmarks.first?.positionRef, "chapter:1")
        XCTAssertEqual(vm.bookmarks.first?.label, "Nice bit")

        let vm2 = VaultReaderViewModel(vaultId: id, fileId: fileId, sessionManager: manager)
        await vm2.load()
        XCTAssertEqual(vm2.bookmarks.count, 1)
        XCTAssertEqual(vm2.bookmarks.first?.positionRef, "chapter:1")
    }

    func testRemoveBookmarkPersists() async throws {
        let (manager, id) = try await makeUnlockedVault()
        let fileId = try await importFixtureEPUB(into: manager, vaultId: id, chapterBodies: ["<p>One.</p>"])
        let vm = VaultReaderViewModel(vaultId: id, fileId: fileId, sessionManager: manager)
        await vm.load()
        await vm.addBookmark()
        let bookmarkId = try XCTUnwrap(vm.bookmarks.first?.id)

        await vm.removeBookmark(id: bookmarkId)

        XCTAssertTrue(vm.bookmarks.isEmpty)
        let vm2 = VaultReaderViewModel(vaultId: id, fileId: fileId, sessionManager: manager)
        await vm2.load()
        XCTAssertTrue(vm2.bookmarks.isEmpty)
    }

    func testAddHighlightCapturesTheCurrentChapterTextAndPersists() async throws {
        let (manager, id) = try await makeUnlockedVault()
        let fileId = try await importFixtureEPUB(into: manager, vaultId: id, chapterBodies: ["<p>Highlight me.</p>", "<p>Other.</p>"])
        let vm = VaultReaderViewModel(vaultId: id, fileId: fileId, sessionManager: manager)
        await vm.load()

        await vm.addHighlight()

        XCTAssertEqual(vm.highlights.count, 1)
        XCTAssertEqual(vm.highlights.first?.positionRef, "chapter:0")
        XCTAssertTrue(vm.highlights.first?.highlightedText.contains("Highlight me.") ?? false)

        let vm2 = VaultReaderViewModel(vaultId: id, fileId: fileId, sessionManager: manager)
        await vm2.load()
        XCTAssertEqual(vm2.highlights.count, 1)
    }

    // MARK: - Lock lifecycle (#526/#668)

    /// The core regression this reopens #526 for: nothing previously
    /// re-checked lock state after `load()`, so a lock that fired while the
    /// screen was already open went unnoticed.
    func testCheckStillUnlockedFlipsWasLockedWhenTheVaultLockedWhileOpen() async throws {
        let (manager, id) = try await makeUnlockedVault()
        let fileId = try await importFixtureEPUB(into: manager, vaultId: id, chapterBodies: ["<p>One.</p>"])
        let vm = VaultReaderViewModel(vaultId: id, fileId: fileId, sessionManager: manager)
        await vm.load()
        XCTAssertFalse(vm.wasLocked)

        await manager.lock(id) // simulates VaultForegroundLockObserver firing while this screen is open

        await vm.checkStillUnlocked()
        XCTAssertTrue(vm.wasLocked)
    }

    func testCheckStillUnlockedIsANoOpWhileStillUnlocked() async throws {
        let (manager, id) = try await makeUnlockedVault()
        let fileId = try await importFixtureEPUB(into: manager, vaultId: id, chapterBodies: ["<p>One.</p>"])
        let vm = VaultReaderViewModel(vaultId: id, fileId: fileId, sessionManager: manager)
        await vm.load()

        await vm.checkStillUnlocked()

        XCTAssertFalse(vm.wasLocked)
    }

    /// `lock()` is the active path (#668, wired to `.vaultContentSecurity()`'s
    /// capture-detected callback) — unlike `checkStillUnlocked()`, it must
    /// actually lock the vault itself, not just notice an external lock.
    func testLockActuallyLocksTheVaultAndFlipsWasLocked() async throws {
        let (manager, id) = try await makeUnlockedVault()
        let fileId = try await importFixtureEPUB(into: manager, vaultId: id, chapterBodies: ["<p>One.</p>"])
        let vm = VaultReaderViewModel(vaultId: id, fileId: fileId, sessionManager: manager)
        await vm.load()

        await vm.lock()

        XCTAssertTrue(vm.wasLocked)
        let stillUnlocked = await manager.isUnlocked(id)
        XCTAssertFalse(stillUnlocked)
    }

    /// A mutating call racing a lock that happened between `load()` and the
    /// call must surface as `wasLocked`, not as a generic `errorMessage` —
    /// the same "notice it, don't just report a scary opaque error" behavior
    /// Kotlin's `launchOrNoticeLock` gives the unified `ReaderViewModel`.
    func testAddBookmarkAfterVaultLocksWhileScreenIsOpenSurfacesWasLockedNotAGenericError() async throws {
        let (manager, id) = try await makeUnlockedVault()
        let fileId = try await importFixtureEPUB(into: manager, vaultId: id, chapterBodies: ["<p>One.</p>"])
        let vm = VaultReaderViewModel(vaultId: id, fileId: fileId, sessionManager: manager)
        await vm.load()
        await manager.lock(id)

        await vm.addBookmark(label: "too late")

        XCTAssertTrue(vm.wasLocked)
        XCTAssertNil(vm.errorMessage)
        XCTAssertTrue(vm.bookmarks.isEmpty)
    }

    func testNavigateToBookmarkUpdatesTheCurrentChapterIndex() async throws {
        let (manager, id) = try await makeUnlockedVault()
        let fileId = try await importFixtureEPUB(into: manager, vaultId: id, chapterBodies: ["<p>One.</p>", "<p>Two.</p>", "<p>Three.</p>"])
        let vm = VaultReaderViewModel(vaultId: id, fileId: fileId, sessionManager: manager)
        await vm.load()
        vm.currentChapterIndex = 2
        await vm.addBookmark()
        vm.currentChapterIndex = 0
        let bookmark = try XCTUnwrap(vm.bookmarks.first)

        vm.navigate(to: bookmark)

        XCTAssertEqual(vm.currentChapterIndex, 2)
    }
}
