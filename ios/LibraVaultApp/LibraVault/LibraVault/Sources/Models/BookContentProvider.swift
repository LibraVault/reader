import Foundation
import PDFKit

struct BookChapter {
    let title: String
    let text: String
}

/// Loads real chapter content for a book from its backing file — the only source of
/// reading content in the app (EPUB, PDF; mobi/cbz/audio throw `.unsupportedFormat`
/// so callers can show an honest "not supported" state). Re-resolves the vault's
/// security-scoped bookmark around the read, since LibraryFileScanner only holds
/// scope briefly during the scan itself, not for the lifetime of the app.
enum BookContentProvider {
    enum ContentError: Error, Equatable {
        case unsupportedFormat
        case missingFileReference
        case vaultUnavailable
    }

    /// Whether `chapters(for:)` has a parser for this format. Exposed so callers can
    /// gate *before* starting work that only makes sense for a narratable book — see
    /// AppState.startPlayback — instead of inferring it from a thrown error, which is
    /// indistinguishable from "right format, unreadable file".
    static func supportsChapterParsing(_ format: MediaFormat) -> Bool {
        format == .epub || format == .pdf
    }

    static func chapters(for book: BookItem, vaultPersistence: VaultPersistence = VaultPersistence()) throws -> [BookChapter] {
        guard supportsChapterParsing(book.format) else {
            throw ContentError.unsupportedFormat
        }
        return try withSecurityScopedAccess(for: book, vaultPersistence: vaultPersistence) { fileURL in
            switch book.format {
            case .epub: return try EPUBParser.parse(fileURL: fileURL)
            case .pdf: return try PDFParser.parse(fileURL: fileURL)
            default: throw ContentError.unsupportedFormat
            }
        }
    }

    /// Raw Markdown source for the Markdown viewer. Parsing into renderable blocks
    /// happens separately (see MarkdownDocumentParser) — this stays a plain file read
    /// so it mirrors chapters(for:)'s vault-resolution shape without depending on it.
    static func markdownSource(for book: BookItem, vaultPersistence: VaultPersistence = VaultPersistence()) throws -> String {
        guard book.format == .markdown else {
            throw ContentError.unsupportedFormat
        }
        return try withSecurityScopedAccess(for: book, vaultPersistence: vaultPersistence) { fileURL in
            try String(contentsOf: fileURL, encoding: .utf8)
        }
    }

    /// Resolves a relative image reference (`./img.png`, `images/pic.jpg`,
    /// `../shared/x.png`) against the Markdown file's own location and reads its
    /// bytes — called eagerly once per image at document-load time (see
    /// ReaderView.loadContent), not lazily during rendering, since the security-scoped
    /// access this needs is only held open for the duration of this call, not for the
    /// lifetime of the view. Never resolves absolute http(s) URLs — LibraVault is
    /// offline-first and doesn't request the `INTERNET`-equivalent network entitlement.
    static func markdownAssetData(
        for book: BookItem,
        relativePath: String,
        vaultPersistence: VaultPersistence = VaultPersistence()
    ) throws -> Data {
        guard book.format == .markdown else {
            throw ContentError.unsupportedFormat
        }
        if relativePath.lowercased().hasPrefix("http://") || relativePath.lowercased().hasPrefix("https://") {
            throw ContentError.unsupportedFormat
        }
        return try withSecurityScopedAccess(for: book, vaultPersistence: vaultPersistence) { fileURL in
            guard let resolvedURL = URL(string: relativePath, relativeTo: fileURL)?.standardizedFileURL else {
                throw ContentError.missingFileReference
            }
            return try Data(contentsOf: resolvedURL)
        }
    }

    /// Opens a PDF for on-screen page rendering (PDFReaderContent's PDFView), not text
    /// extraction — unlike chapters(for:)/markdownSource(for:), which read a file's
    /// full content upfront and can release the vault's security scope immediately,
    /// PDFKit's PDFView lazily rereads page data from disk as the user pages/scrolls,
    /// so scope must stay open for as long as the reader is displaying the document.
    /// Returns the opened document plus an `endAccess` closure the caller must invoke
    /// exactly once (e.g. ReaderView's onDisappear) to release that held-open scope.
    static func openPDFDocument(
        for book: BookItem,
        vaultPersistence: VaultPersistence = VaultPersistence()
    ) throws -> (document: PDFDocument, endAccess: () -> Void) {
        guard book.format == .pdf else {
            throw ContentError.unsupportedFormat
        }
        let (fileURL, vaultURL) = try resolveFileAndVaultURL(for: book, vaultPersistence: vaultPersistence)

        let didStartAccessing = vaultURL.startAccessingSecurityScopedResource()
        let endAccess: () -> Void = {
            if didStartAccessing { vaultURL.stopAccessingSecurityScopedResource() }
        }

        guard let document = PDFDocument(url: fileURL) else {
            endAccess()
            throw PDFParser.ParseError.invalidDocument
        }
        guard document.pageCount > 0 else {
            endAccess()
            throw PDFParser.ParseError.emptyDocument
        }
        return (document, endAccess)
    }

    /// Resolves the book's vault security-scoped bookmark and holds the scope open
    /// for the duration of `body`. Shared by chapters(for:) and markdownSource(for:)
    /// since both need the same "resolve vault → start scope → read file" sequence.
    private static func withSecurityScopedAccess<T>(
        for book: BookItem,
        vaultPersistence: VaultPersistence,
        _ body: (URL) throws -> T
    ) throws -> T {
        let (fileURL, vaultURL) = try resolveFileAndVaultURL(for: book, vaultPersistence: vaultPersistence)

        let didStartAccessing = vaultURL.startAccessingSecurityScopedResource()
        defer { if didStartAccessing { vaultURL.stopAccessingSecurityScopedResource() } }

        return try body(fileURL)
    }

    /// Shared by withSecurityScopedAccess and openPDFDocument — the latter can't use
    /// withSecurityScopedAccess's own scope handling since it needs the scope to
    /// outlive this call, not just the body closure.
    private static func resolveFileAndVaultURL(
        for book: BookItem,
        vaultPersistence: VaultPersistence
    ) throws -> (fileURL: URL, vaultURL: URL) {
        guard let fileURL = book.fileURL, let vaultId = book.vaultId else {
            throw ContentError.missingFileReference
        }
        guard let vault = vaultPersistence.loadVaults().first(where: { $0.id == vaultId }),
              let resolvedVaultURL = vaultPersistence.resolvedURL(for: vault) else {
            throw ContentError.vaultUnavailable
        }
        return (fileURL, resolvedVaultURL)
    }
}
