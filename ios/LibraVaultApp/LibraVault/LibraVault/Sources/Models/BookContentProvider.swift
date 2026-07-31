import Foundation

struct BookChapter {
    let title: String
    let text: String
}

/// Loads real chapter content for a book from its backing file, replacing
/// MockChapterContent for formats with a real parser wired up (EPUB now — PDF and
/// others follow in later work; unsupported formats throw `.unsupportedFormat` so
/// callers can fall back). Re-resolves the vault's security-scoped bookmark around
/// the read, since LibraryFileScanner only holds scope briefly during the scan
/// itself, not for the lifetime of the app.
enum BookContentProvider {
    enum ContentError: Error, Equatable {
        case unsupportedFormat
        case missingFileReference
        case vaultUnavailable
    }

    static func chapters(for book: BookItem, vaultPersistence: VaultPersistence = VaultPersistence()) throws -> [BookChapter] {
        guard book.format == .epub else { throw ContentError.unsupportedFormat }
        guard let fileURL = book.fileURL, let vaultId = book.vaultId else {
            throw ContentError.missingFileReference
        }
        guard let vault = vaultPersistence.loadVaults().first(where: { $0.id == vaultId }),
              let resolvedVaultURL = vaultPersistence.resolvedURL(for: vault) else {
            throw ContentError.vaultUnavailable
        }

        let didStartAccessing = resolvedVaultURL.startAccessingSecurityScopedResource()
        defer { if didStartAccessing { resolvedVaultURL.stopAccessingSecurityScopedResource() } }

        return try EPUBParser.parse(fileURL: fileURL)
    }
}
