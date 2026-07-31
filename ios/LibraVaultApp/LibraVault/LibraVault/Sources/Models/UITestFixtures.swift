#if DEBUG
import Foundation

/// Bootstraps a real (non-mock) vault backed by a file the app writes into its own
/// sandbox, so LibraVaultUITests can navigate against a genuinely scanned book
/// instead of any hardcoded library. Only activates when the UI test target passes
/// `launchArgument` (see LibraVaultUITests.swift) — never on a normal launch, and
/// compiled out of Release builds entirely.
enum UITestFixtures {
    static let launchArgument = "-uiTestFixtureVault"
    private static let vaultId = "ui-test-fixture-vault"

    /// Idempotent: safe to call on every launch, since UI tests relaunch the app
    /// (and its persisted UserDefaults/container) across test methods within a run.
    static func ensureVault(persistence: VaultPersistence) {
        guard ProcessInfo.processInfo.arguments.contains(launchArgument) else { return }

        var vaults = persistence.loadVaults()
        guard !vaults.contains(where: { $0.id == vaultId }) else { return }

        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("UITestFixtureVault", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        // Content doesn't matter yet — LibraryFileScanner only reads the filename and
        // extension (Phase 1). Once real EPUB parsing lands, this should become a
        // genuinely valid minimal EPUB so tests exercising real content still pass.
        try? Data().write(to: folder.appendingPathComponent("To Kill a Mockingbird.epub"))

        guard let bookmarkData = try? folder.bookmarkData() else { return }
        vaults.append(Vault(id: vaultId, displayName: "UI Test Fixtures", bookmarkData: bookmarkData))
        persistence.save(vaults)
    }
}
#endif
