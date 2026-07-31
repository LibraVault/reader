import Foundation

struct BookChapter {
    let title: String
    let text: String
}

/// Loads real chapter content for a book from its backing file, replacing
/// MockChapterContent for formats with a real parser wired up (EPUB, PDF — mobi/cbz/
/// audio throw `.unsupportedFormat` so callers can show an honest "not supported"
/// state instead of falling back to fake content). Re-resolves the vault's
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
        guard let fileURL = book.fileURL, let vaultId = book.vaultId else {
            throw ContentError.missingFileReference
        }
        guard let vault = vaultPersistence.loadVaults().first(where: { $0.id == vaultId }),
              let resolvedVaultURL = vaultPersistence.resolvedURL(for: vault) else {
            throw ContentError.vaultUnavailable
        }

        let didStartAccessing = resolvedVaultURL.startAccessingSecurityScopedResource()
        defer { if didStartAccessing { resolvedVaultURL.stopAccessingSecurityScopedResource() } }

        switch book.format {
        case .epub: return try EPUBParser.parse(fileURL: fileURL)
        case .pdf: return try PDFParser.parse(fileURL: fileURL)
        default: throw ContentError.unsupportedFormat
        }
    }
}
