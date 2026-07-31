#if DEBUG
import Foundation
import ZIPFoundation

/// Bootstraps a real (non-mock) vault backed by a file the app writes into its own
/// sandbox, so LibraVaultUITests can navigate against a genuinely scanned, genuinely
/// parseable book instead of any hardcoded library or mock content. Only activates
/// when the UI test target passes `launchArgument` (see LibraVaultUITests.swift) —
/// never on a normal launch, and compiled out of Release builds entirely.
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
        try? writeFixtureEPUB(to: folder.appendingPathComponent("To Kill a Mockingbird.epub"))

        guard let bookmarkData = try? folder.bookmarkData() else { return }
        vaults.append(Vault(id: vaultId, displayName: "UI Test Fixtures", bookmarkData: bookmarkData))
        persistence.save(vaults)
    }

    /// A real, valid, single-chapter EPUB — there's no mock content fallback for
    /// EPUBParser to degrade to anymore, so UI tests need a genuinely parseable file
    /// to navigate real content, not just a placeholder with the right extension.
    /// The chapter's heading matches what LibraVaultUITests.testPlayerChaptersSheetShowsAllChapters
    /// asserts on, so real parsed content and the UI test's expectation stay in sync.
    private static func writeFixtureEPUB(to destinationURL: URL) throws {
        let sourceDir = FileManager.default.temporaryDirectory.appendingPathComponent("UITestFixtureEPUBSource-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: sourceDir) }
        let oebpsDir = sourceDir.appendingPathComponent("OEBPS", isDirectory: true)
        let metaInfDir = sourceDir.appendingPathComponent("META-INF", isDirectory: true)
        try FileManager.default.createDirectory(at: oebpsDir, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: metaInfDir, withIntermediateDirectories: true)

        try """
        <?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
        """.write(to: metaInfDir.appendingPathComponent("container.xml"), atomically: true, encoding: .utf8)

        try """
        <?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <manifest><item id="chap0" href="chap0.xhtml" media-type="application/xhtml+xml"/></manifest>
          <spine><itemref idref="chap0"/></spine>
        </package>
        """.write(to: oebpsDir.appendingPathComponent("content.opf"), atomically: true, encoding: .utf8)

        try """
        <html><body>
        <h1>Chapter 1: The Beginning</h1>
        <p>It was a bright cold day, and the UI test suite needed real chapter text to read aloud.</p>
        </body></html>
        """.write(to: oebpsDir.appendingPathComponent("chap0.xhtml"), atomically: true, encoding: .utf8)

        try? FileManager.default.removeItem(at: destinationURL)
        try FileManager().zipItem(at: sourceDir, to: destinationURL, shouldKeepParent: false)
    }
}
#endif
