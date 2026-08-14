import Foundation

/// A user-granted folder location scanned for books/audiobooks. Persisted across
/// launches via a security-scoped bookmark — the iOS/UIDocumentPicker counterpart to
/// Android's Storage Access Framework `ACTION_OPEN_DOCUMENT_TREE` + persistable URI
/// permission (see VaultManager.persistPermission on the Android side).
struct Vault: Identifiable, Equatable, Codable {
    let id: String
    let displayName: String
    let bookmarkData: Data
}

/// Loads/saves the vault list to UserDefaults and resolves each vault's bookmark back
/// to a URL usable for scanning. Kept separate from AppState (a plain struct, not an
/// ObservableObject) so it's trivially testable against an isolated UserDefaults suite
/// instead of the real `.standard` defaults.
struct VaultPersistence {
    private let defaults: UserDefaults
    private let key = "xyz.libravault.vaults"

    /// Stable id for the permanent "Imported" vault (see `importedVault()`) — fixed
    /// rather than a fresh UUID like `makeVault` generates, so re-deriving it (every
    /// time a file is shared in) always finds the one already-persisted vault instead
    /// of minting a duplicate.
    private static let importedVaultId = "xyz.libravault.importedVault"

    /// Where `importedVault()` creates its backing folder. The real sandboxed
    /// Documents directory in production; overridable in tests so they don't write
    /// into the Documents folder the test host process actually uses on disk.
    private let importedVaultBaseDirectory: URL

    init(
        defaults: UserDefaults = .standard,
        importedVaultBaseDirectory: URL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    ) {
        self.defaults = defaults
        self.importedVaultBaseDirectory = importedVaultBaseDirectory
    }

    func loadVaults() -> [Vault] {
        guard let data = defaults.data(forKey: key),
              let vaults = try? JSONDecoder().decode([Vault].self, from: data) else {
            return []
        }
        return vaults
    }

    func save(_ vaults: [Vault]) {
        guard let data = try? JSONEncoder().encode(vaults) else { return }
        defaults.set(data, forKey: key)
    }

    /// Captures a security-scoped bookmark for a URL handed back by `.fileImporter`
    /// (a folder pick via `UIDocumentPickerViewController`) so it can be re-resolved on
    /// a future launch without asking the user to pick it again.
    ///
    /// Per Apple's docs, `startAccessingSecurityScopedResource()` returns `false` when
    /// the URL doesn't actually require security-scoped access (e.g. a plain local URL
    /// in tests) — that's not a failure, the resource is still accessible, so the
    /// return value only decides whether a matching `stop` call is needed, not whether
    /// bookmark creation should proceed.
    func makeVault(from pickedURL: URL) throws -> Vault {
        let didStartAccessing = pickedURL.startAccessingSecurityScopedResource()
        defer { if didStartAccessing { pickedURL.stopAccessingSecurityScopedResource() } }
        let bookmarkData = try pickedURL.bookmarkData()
        return Vault(id: UUID().uuidString, displayName: pickedURL.lastPathComponent, bookmarkData: bookmarkData)
    }

    /// Resolves a persisted vault's bookmark back to a usable URL. Returns nil if the
    /// bookmark can't be resolved (e.g. the folder was deleted or moved).
    func resolvedURL(for vault: Vault) -> URL? {
        var isStale = false
        return try? URL(resolvingBookmarkData: vault.bookmarkData, bookmarkDataIsStale: &isStale)
    }

    /// Returns the permanent, app-owned vault that files opened/shared into LibraVault
    /// via "Open In"/the share sheet get copied into (see AppState.importSharedFile) —
    /// created on first use and reused after that, matched by `importedVaultId` rather
    /// than a fresh UUID so this never mints a second one. Its folder lives inside the
    /// app's own sandbox, so — unlike `makeVault`, which captures a *security-scoped*
    /// bookmark for a folder the user picked outside the sandbox — no security scope
    /// needs to be requested to create this one's bookmark; `startAccessingSecurityScopedResource`
    /// on an in-sandbox URL is a documented no-op that still returns `true` some of the
    /// time, so this deliberately doesn't call it at all.
    func importedVault() throws -> Vault {
        if let existing = loadVaults().first(where: { $0.id == Self.importedVaultId }) {
            return existing
        }
        let folderURL = importedVaultBaseDirectory.appendingPathComponent("Imported", isDirectory: true)
        try FileManager.default.createDirectory(at: folderURL, withIntermediateDirectories: true)
        let bookmarkData = try folderURL.bookmarkData()
        return Vault(id: Self.importedVaultId, displayName: "Imported", bookmarkData: bookmarkData)
    }
}
