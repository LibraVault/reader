import XCTest
@testable import LibraVault

final class LibraryViewLogicTests: XCTestCase {

    // MARK: - LibraryFormatFilter

    func testAllFilterMatchesEveryFormat() {
        XCTAssertTrue(LibraryFormatFilter.all.matches(.epub))
        XCTAssertTrue(LibraryFormatFilter.all.matches(.pdf))
        XCTAssertTrue(LibraryFormatFilter.all.matches(.markdown))
        XCTAssertTrue(LibraryFormatFilter.all.matches(.mobi))
        XCTAssertTrue(LibraryFormatFilter.all.matches(.cbz))
        XCTAssertTrue(LibraryFormatFilter.all.matches(.mp3))
        XCTAssertTrue(LibraryFormatFilter.all.matches(.m4b))
    }

    func testEpubFilterMatchesOnlyEpub() {
        XCTAssertTrue(LibraryFormatFilter.epub.matches(.epub))
        XCTAssertFalse(LibraryFormatFilter.epub.matches(.pdf))
        XCTAssertFalse(LibraryFormatFilter.epub.matches(.mobi))
    }

    func testPdfFilterMatchesOnlyPdf() {
        XCTAssertTrue(LibraryFormatFilter.pdf.matches(.pdf))
        XCTAssertFalse(LibraryFormatFilter.pdf.matches(.epub))
    }

    func testMarkdownFilterMatchesOnlyMarkdown() {
        XCTAssertTrue(LibraryFormatFilter.markdown.matches(.markdown))
        XCTAssertFalse(LibraryFormatFilter.markdown.matches(.epub))
        XCTAssertFalse(LibraryFormatFilter.markdown.matches(.pdf))
        XCTAssertFalse(LibraryFormatFilter.markdown.matches(.mp3))
    }

    /// The chip row is driven by `allCases`, so its order is the on-screen order —
    /// documents grouped together, audio last.
    func testFilterCasesAreInDisplayOrder() {
        XCTAssertEqual(LibraryFormatFilter.allCases, [.all, .epub, .pdf, .markdown, .audio])
    }

    func testAudioFilterMatchesOnlyAudioFormats() {
        for format: MediaFormat in [.mp3, .m4b, .aac, .flac, .ogg, .opus] {
            XCTAssertTrue(LibraryFormatFilter.audio.matches(format), "\(format) should match the Audio filter")
        }
        for format: MediaFormat in [.epub, .pdf, .markdown, .mobi, .cbz] {
            XCTAssertFalse(LibraryFormatFilter.audio.matches(format), "\(format) should not match the Audio filter")
        }
    }

    // MARK: - MediaFormat.isAudio

    func testIsAudioIsTrueOnlyForAudioFormats() {
        XCTAssertTrue(MediaFormat.mp3.isAudio)
        XCTAssertTrue(MediaFormat.m4b.isAudio)
        XCTAssertTrue(MediaFormat.aac.isAudio)
        XCTAssertTrue(MediaFormat.flac.isAudio)
        XCTAssertTrue(MediaFormat.ogg.isAudio)
        XCTAssertTrue(MediaFormat.opus.isAudio)
        XCTAssertFalse(MediaFormat.epub.isAudio)
        XCTAssertFalse(MediaFormat.pdf.isAudio)
        XCTAssertFalse(MediaFormat.markdown.isAudio)
        XCTAssertFalse(MediaFormat.mobi.isAudio)
        XCTAssertFalse(MediaFormat.cbz.isAudio)
    }

    // MARK: - BookItem(from: BookData) threads format through

    func testBookItemFromBookDataPreservesFormat() {
        let epubData = BookData(id: "1", title: "T", author: "A", format: .epub)
        let pdfData = BookData(id: "2", title: "T", author: "A", format: .pdf)

        XCTAssertEqual(BookItem(from: epubData).format, .epub)
        XCTAssertEqual(BookItem(from: pdfData).format, .pdf)
    }

    func testBookItemManualInitDefaultsToEpub() {
        let book = BookItem(id: "1", title: "T", author: "A")
        XCTAssertEqual(book.format, .epub)
    }

    // MARK: - generatedCoverPaletteIndex
    //
    // Direct regression coverage for the bug fixed during Phase 2 review: this used to
    // key off book.id.hashValue, which Swift reseeds per process launch, so covers
    // would silently reshuffle colors on every relaunch. Deterministic-within-a-run
    // was the whole point of switching to a UTF-8 byte sum.

    func testCoverPaletteIndexIsDeterministicForTheSameId() {
        let book = BookItem(id: "same-id-123", title: "T", author: "A")
        let first = generatedCoverPaletteIndex(for: book)
        let second = generatedCoverPaletteIndex(for: book)
        XCTAssertEqual(first, second)
    }

    func testCoverPaletteIndexIsAlwaysInBounds() {
        for id in ["", "1", "a-very-long-book-id-string-well-past-typical-length", "🎉📚"] {
            let index = generatedCoverPaletteIndex(for: BookItem(id: id, title: "T", author: "A"))
            XCTAssertGreaterThanOrEqual(index, 0)
            XCTAssertLessThan(index, generatedCoverPalette.count)
        }
    }

    // MARK: - initials(for:) (#308)
    //
    // Mirrors Android's `initialsFor` test cases in
    // core/ui/src/test/kotlin/.../GeneratedCoverDeterminismTest.kt, same rules.

    func testInitialsExtractTwoUppercaseLettersFromMultiWordTitles() {
        XCTAssertEqual(initials(for: "The Pragmatic Programmer"), "TP")
        XCTAssertEqual(initials(for: "homo homini"), "HH")
        XCTAssertEqual(initials(for: "Sapiens: A Brief History of Humankind"), "SA")
    }

    func testInitialsExtractTwoLettersFromSingleWordTitles() {
        XCTAssertEqual(initials(for: "Dune"), "DU")
        XCTAssertEqual(initials(for: "Be"), "BE")
    }

    func testInitialsFallBackToQuestionMarkForAnEmptyTitle() {
        XCTAssertEqual(initials(for: ""), "?")
        XCTAssertEqual(initials(for: "   "), "?")
    }

    func testInitialsAreUppercase() {
        XCTAssertEqual(initials(for: "hello world"), "HW")
    }

    // MARK: - CoverFormatBadge(format:) (#308)

    func testFormatBadgeMapsDocumentFormatsToThemselves() {
        XCTAssertEqual(CoverFormatBadge(format: .epub)?.label, "EPUB")
        XCTAssertEqual(CoverFormatBadge(format: .pdf)?.label, "PDF")
        XCTAssertEqual(CoverFormatBadge(format: .markdown)?.label, "MD")
    }

    func testFormatBadgeMapsEveryAudioFormatToAudio() {
        for format: MediaFormat in [.mp3, .m4b, .aac, .flac, .ogg, .opus] {
            XCTAssertEqual(CoverFormatBadge(format: format)?.label, "Audio", "for \(format)")
        }
    }

    /// mobi/cbz are recognized by the scanner but not yet readable (see
    /// BookContentProvider) — no badge for them yet, `CoverArtView` falls back to the
    /// original generic treatment rather than promising a format it can't actually open.
    func testFormatBadgeIsNilForNotYetSupportedFormats() {
        XCTAssertNil(CoverFormatBadge(format: .mobi))
        XCTAssertNil(CoverFormatBadge(format: .cbz))
    }

    func testFormatBadgeSymbolsAreDistinctPerCase() {
        let symbols = [
            CoverFormatBadge.epub.symbolName,
            CoverFormatBadge.pdf.symbolName,
            CoverFormatBadge.markdown.symbolName,
            CoverFormatBadge.audio.symbolName,
        ]
        XCTAssertEqual(Set(symbols).count, symbols.count, "each badge should use its own SF Symbol")
    }

    // MARK: - AppError

    func testLibraryLoadFailedIncludesTheUnderlyingReason() {
        let error = AppError.libraryLoadFailed("disk full")
        XCTAssertEqual(error.errorDescription, "Failed to load library: disk full")
    }

    func testBookNotFoundAndStorageAccessDeniedHaveFixedMessages() {
        XCTAssertEqual(AppError.bookNotFound.errorDescription, "Book not found")
        XCTAssertEqual(AppError.storageAccessDenied.errorDescription, "Storage access denied")
        XCTAssertEqual(AppError.unsupportedFileType.errorDescription, "This file type isn't supported")
        XCTAssertEqual(AppError.fileImportFailed.errorDescription, "Couldn't import that file")
        XCTAssertEqual(
            AppError.invalidVaultSelection.errorDescription,
            "Please select a folder, not a file — pick the folder that contains your books."
        )
    }

    // MARK: - emptyLibraryHeadline / emptyLibraryMessage

    func testEmptyLibraryGuidesToSettingsWhenNoVaultsConfigured() {
        XCTAssertEqual(emptyLibraryHeadline(hasVaults: false), "Start Your Library")
        XCTAssertTrue(emptyLibraryMessage(hasVaults: false).contains("Settings"))
        XCTAssertTrue(emptyLibraryMessage(hasVaults: false).contains("Add Vault"))
    }

    func testEmptyLibraryMessageDoesNotMentionSettingsWhenVaultsExist() {
        XCTAssertEqual(emptyLibraryHeadline(hasVaults: true), "No Books Found")
        XCTAssertFalse(emptyLibraryMessage(hasVaults: true).contains("Settings"))
    }

    func testCoverPaletteIndexDiffersForDifferentIds() {
        // Not a hard guarantee for arbitrary ids (a collision is possible), but a
        // handful of simple sequential ids should spread across more than one slot —
        // otherwise every book in the grid looks the same.
        let ids = ["1", "2", "3", "4", "5"]
        let indices = Set(ids.map { generatedCoverPaletteIndex(for: BookItem(id: $0, title: "T", author: "A")) })
        XCTAssertGreaterThan(indices.count, 1)
    }
}
