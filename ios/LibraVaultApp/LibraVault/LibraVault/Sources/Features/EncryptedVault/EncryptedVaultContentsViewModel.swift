import Foundation

/// Status of one file within a multi-file import batch.
enum ImportItemStatus: Equatable {
    case pending
    case importing
    case done
    case error(String)
}

/// One row in the import-progress sheet. `id` is a fresh `UUID`, not the
/// file's URL — two picked files can share a display name (same filename in
/// different source folders), and `Identifiable` needs a genuinely stable,
/// unique key for `ForEach`/`List` to diff against, not something that could
/// collide.
struct ImportItem: Identifiable, Equatable {
    let id = UUID()
    let displayName: String
    var status: ImportItemStatus
}

enum EncryptedVaultImportError: Error, LocalizedError {
    case unsupportedFormat(String)
    case cannotOpenFile(String)

    var errorDescription: String? {
        switch self {
        case .unsupportedFormat(let name):
            return "\(name) isn't a format LibraVault reads."
        case .cannotOpenFile(let name):
            return "Couldn't open \(name)."
        }
    }
}

/// Lists an unlocked vault's manifest entries, imports new files into it, and
/// owns locking it again.
@MainActor
final class EncryptedVaultContentsViewModel: ObservableObject {

    let vaultId: String

    @Published private(set) var entries: [VaultManifestEntry] = []
    @Published private(set) var isLocked = false
    @Published var errorMessage: String?
    @Published private(set) var importItems: [ImportItem] = []
    @Published private(set) var isImporting = false

    private let sessionManager: VaultSessionManager

    init(vaultId: String, sessionManager: VaultSessionManager) {
        self.vaultId = vaultId
        self.sessionManager = sessionManager
    }

    func refresh() async {
        guard await sessionManager.isUnlocked(vaultId) else {
            isLocked = true
            return
        }
        do {
            entries = try await sessionManager.requireUnlocked(vaultId).listEntries()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func lock() async {
        await sessionManager.lock(vaultId)
        isLocked = true
    }

    /// Imports every picked URL into this vault, one at a time — `VaultStore`
    /// isn't safe for concurrent callers (see its own doc comment), so this
    /// deliberately awaits each import in sequence rather than fanning out
    /// with `TaskGroup`, matching Android's `VaultContentsViewModel
    /// .onFilesPicked`'s own sequential-for-loop choice for the identical
    /// reason. Per-item status is published as it happens so the sheet
    /// updates live, not just once at the end.
    func importFiles(urls: [URL]) async {
        guard await sessionManager.isUnlocked(vaultId) else {
            isLocked = true
            return
        }
        guard !urls.isEmpty else { return }

        isImporting = true
        defer { isImporting = false }
        importItems = urls.map { ImportItem(displayName: $0.lastPathComponent, status: .pending) }

        let store = await sessionManager.requireUnlocked(vaultId)
        for (index, url) in urls.enumerated() {
            importItems[index].status = .importing
            do {
                try await importOne(url: url, into: store)
                importItems[index].status = .done
            } catch {
                importItems[index].status = .error(error.localizedDescription)
            }
        }

        await refresh()
    }

    /// Clears the finished import batch's progress list — called once the
    /// user dismisses the progress sheet, so re-opening "Import" starts from
    /// a clean slate rather than showing the previous batch's results.
    func clearImportItems() {
        importItems = []
    }

    private func importOne(url: URL, into store: VaultStore) async throws {
        let didStartAccessing = url.startAccessingSecurityScopedResource()
        defer { if didStartAccessing { url.stopAccessingSecurityScopedResource() } }

        guard let format = LibraryFileScanner.extensionFormats[url.pathExtension.lowercased()] else {
            throw EncryptedVaultImportError.unsupportedFormat(url.lastPathComponent)
        }
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        let size = (attributes[.size] as? NSNumber)?.int64Value ?? 0
        let title = url.deletingPathExtension().lastPathComponent

        // Cover art goes through the same hardened decode/downsample path
        // CoverArtCache.save uses for non-vault imports (OOM/corrupt-image
        // defense, 512px cap) — but deliberately via extractRawCoverData +
        // downsampledJPEG directly, never extractCoverPath/CoverArtCache
        // itself, so a vault import's cover art never touches the shared
        // plaintext cover-art cache. See extractRawCoverData's doc comment.
        var coverArt: Data?
        if let rawCover = await CoverArtExtractor.extractRawCoverData(format: format, fileURL: url) {
            coverArt = CoverArtCache.downsampledJPEG(from: rawCover, maxDimension: CoverArtCache.maxCoverPx)
        }

        guard let input = InputStream(url: url) else {
            throw EncryptedVaultImportError.cannotOpenFile(url.lastPathComponent)
        }
        input.open()
        defer { input.close() }

        try store.importFile(
            input: input,
            declaredSize: size,
            title: title,
            author: nil,
            format: String(describing: format),
            coverArt: coverArt
        )
    }
}
