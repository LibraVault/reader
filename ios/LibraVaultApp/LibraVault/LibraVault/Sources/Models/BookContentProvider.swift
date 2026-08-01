import Foundation

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

    static func chapters(for book: BookItem, vaultPersistence: VaultPersistence = VaultPersistence()) throws -> [BookChapter] {
        guard book.format == .epub || book.format == .pdf else {
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

    /// Resolves the book's vault security-scoped bookmark and holds the scope open
    /// for the duration of `body`. Shared by chapters(for:) and markdownSource(for:)
    /// since both need the same "resolve vault → start scope → read file" sequence.
    private static func withSecurityScopedAccess<T>(
        for book: BookItem,
        vaultPersistence: VaultPersistence,
        _ body: (URL) throws -> T
    ) throws -> T {
        guard let fileURL = book.fileURL, let vaultId = book.vaultId else {
            throw ContentError.missingFileReference
        }
        guard let vault = vaultPersistence.loadVaults().first(where: { $0.id == vaultId }),
              let resolvedVaultURL = vaultPersistence.resolvedURL(for: vault) else {
            throw ContentError.vaultUnavailable
        }

        let didStartAccessing = resolvedVaultURL.startAccessingSecurityScopedResource()
        defer { if didStartAccessing { resolvedVaultURL.stopAccessingSecurityScopedResource() } }

        return try body(fileURL)
    }
}
