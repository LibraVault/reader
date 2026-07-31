import Foundation

/// Real (non-mock) filesystem scan of a vault folder for known book/audiobook file
/// extensions. This is the iOS-native counterpart to Android's FileScanner +
/// MetadataExtractor (core:storage): deliberately simpler, since it only reads the
/// filename and extension — no embedded metadata (title/author/cover/duration)
/// extraction, which is the Phase D core:storage KMP work described in
/// DomainBridge.swift. Matches Android's two-phase scan in spirit (stub entries first,
/// metadata enrichment later) by only implementing phase one for now.
enum LibraryFileScanner {
    static let extensionFormats: [String: MediaFormat] = [
        "epub": .epub,
        "pdf": .pdf,
        "mobi": .mobi,
        "cbz": .cbz,
        "mp3": .mp3,
        "m4b": .m4b,
        "m4a": .aac,
        "aac": .aac,
        "flac": .flac,
        "ogg": .ogg,
        "opus": .opus,
    ]

    /// Walks `resolvedURL` recursively and returns one `BookData` per recognized file.
    /// `vault.id` is folded into each book's id so the same file rescanned across two
    /// different vaults (unlikely, but possible if vaults overlap) doesn't collide.
    static func scan(vault: Vault, resolvedURL: URL) -> [BookData] {
        let didStartAccessing = resolvedURL.startAccessingSecurityScopedResource()
        defer { if didStartAccessing { resolvedURL.stopAccessingSecurityScopedResource() } }

        guard let enumerator = FileManager.default.enumerator(
            at: resolvedURL,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles]
        ) else {
            return []
        }

        var results: [BookData] = []
        for case let fileURL as URL in enumerator {
            guard let format = extensionFormats[fileURL.pathExtension.lowercased()] else { continue }
            let title = fileURL.deletingPathExtension().lastPathComponent
            results.append(BookData(
                id: "vault:\(vault.id):\(fileURL.path)",
                title: title,
                author: "",
                format: format,
                fileURL: fileURL,
                vaultId: vault.id
            ))
        }
        return results
    }
}
