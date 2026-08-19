import Foundation

/// One Encrypted Vault as known to `VaultRegistry` — everything needed to find
/// and label it *before* it's unlocked. `id` is an opaque directory name (a
/// random UUID, not derived from `displayName`), matching the same
/// "opaque id, never the real name, on disk" shape used for individual vault
/// files — though here the *registry's* own file legitimately stores
/// `displayName` in the clear, since a vault picker has to show something
/// before the vault is unlocked. This is a deliberate, accepted residual leak
/// (a vault's existence, count, and chosen name), not an oversight — see
/// `docs/threat-model.md`'s "Accepted residual leaks" section. Mirrors
/// Android's `VaultRegistryEntryDto` (`core/vaultstore`).
struct VaultRegistryEntry: Codable, Equatable {
    let id: String
    let displayName: String
    let createdAtEpochMillis: Int64
}

private struct VaultRegistryFile: Codable {
    var entries: [VaultRegistryEntry] = []
}

enum VaultRegistryError: Error, Equatable {
    /// `add` was called with an id that's already registered.
    case duplicateId(String)
}

/// Tracks the set of Encrypted Vaults that exist on this device, independent
/// of any single vault's own contents. Persisted as JSON directly under
/// `baseDir` — a sibling of the per-vault subdirectories it lists, not inside
/// any of them. Mirrors Android's `VaultRegistry` object (`core/vaultstore`).
///
/// Deliberately free functions over a `baseDir` parameter, not a type reading
/// app sandbox paths directly, so it's testable against a temporary directory
/// without a real device.
enum VaultRegistry {

    private static let fileName = "vaults.json"

    /// On-disk path for a given `id`'s own vault directory, under `baseDir`.
    static func vaultDir(baseDir: URL, id: String) -> URL {
        baseDir.appendingPathComponent(id, isDirectory: true)
    }

    static func list(baseDir: URL) -> [VaultRegistryEntry] {
        let file = baseDir.appendingPathComponent(fileName)
        guard let data = try? Data(contentsOf: file) else { return [] }
        guard let decoded = try? JSONDecoder().decode(VaultRegistryFile.self, from: data) else { return [] }
        return decoded.entries
    }

    /// - Throws: `VaultRegistryError.duplicateId` if `entry.id` is already registered.
    static func add(baseDir: URL, entry: VaultRegistryEntry) throws {
        let current = list(baseDir: baseDir)
        guard !current.contains(where: { $0.id == entry.id }) else {
            throw VaultRegistryError.duplicateId(entry.id)
        }
        try writeAll(baseDir: baseDir, entries: current + [entry])
    }

    /// Removes `id` from the registry. Does not touch the vault directory
    /// itself or delete anything — callers that mean to delete a vault must
    /// do that separately, and should call this FIRST: an orphaned directory
    /// is a better failure mode than a registry entry pointing at nothing.
    /// A no-op if `id` isn't registered.
    static func remove(baseDir: URL, id: String) throws {
        try writeAll(baseDir: baseDir, entries: list(baseDir: baseDir).filter { $0.id != id })
    }

    /// A no-op if `id` isn't registered.
    static func rename(baseDir: URL, id: String, newDisplayName: String) throws {
        let updated = list(baseDir: baseDir).map { entry in
            entry.id == id
                ? VaultRegistryEntry(id: entry.id, displayName: newDisplayName, createdAtEpochMillis: entry.createdAtEpochMillis)
                : entry
        }
        try writeAll(baseDir: baseDir, entries: updated)
    }

    /// `Data.write(to:options:.atomic)` writes to a temp file in the same
    /// directory and renames it into place, so a crash mid-write can never
    /// leave a half-written registry file — readers always see either the
    /// old, complete version or the new, complete version, never a truncated
    /// JSON blob. Same guarantee as Android's write-to-temp-then-rename.
    private static func writeAll(baseDir: URL, entries: [VaultRegistryEntry]) throws {
        try FileManager.default.createDirectory(at: baseDir, withIntermediateDirectories: true)
        let data = try JSONEncoder().encode(VaultRegistryFile(entries: entries))
        try data.write(to: baseDir.appendingPathComponent(fileName), options: .atomic)
    }
}
