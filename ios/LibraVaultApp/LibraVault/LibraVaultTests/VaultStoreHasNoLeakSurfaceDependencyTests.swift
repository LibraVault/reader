import XCTest

/// Regression guard for the leak-closure guarantee: verifies at test time
/// that no file under `Sources/VaultStore/` references a plaintext-
/// persistence/cache type. Swift's equivalent of Android's
/// `VaultStoreHasNoLeakSurfaceDependencyTest`'s classpath-reachability
/// check — see the design decision posted to
/// https://github.com/LibraVault/reader/issues/201#issuecomment-5337718396
/// for why this is a static source scan rather than a structural
/// (linker-enforced) guarantee: this app has no per-module compilation
/// boundary the way `core:vaultstore` (a separate Gradle module) does, so
/// there's no "the type simply isn't reachable" mechanism available here —
/// this is the best available proxy for "someone adds a convenience import
/// later," the exact regression Android's test protects against. The
/// tradeoff, stated plainly: unlike Android's version, a violation here
/// fails a *test*, not the *build* — it only catches the regression if this
/// test suite actually runs.
///
/// Locates `Sources/VaultStore/` via `#filePath` (this test file's own
/// absolute path on the host Mac) — works because this test runs in the
/// Simulator, which shares the host filesystem, unlike a real device or an
/// app-bundle resource. Skips (not fails) if that path can't be found,
/// rather than silently passing on zero files checked.
final class VaultStoreHasNoLeakSurfaceDependencyTests: XCTestCase {

    /// Identifiers that must never appear referenced from
    /// `Sources/VaultStore/**/*.swift` — the app's existing plaintext
    /// persistence/cache types. A legitimate new plaintext type added later
    /// needs adding here too, same as Android's test would need a new
    /// `Class.forName` assertion for a new leak surface.
    private static let forbiddenIdentifiers = ["ReadingDataPersistence", "CoverArtCache"]

    private func vaultStoreSourceDirectory() throws -> URL {
        let thisFile = URL(fileURLWithPath: #filePath)
        // .../LibraVault/LibraVaultTests/VaultStoreHasNoLeakSurfaceDependencyTests.swift
        //   -> .../LibraVault/LibraVault/Sources/VaultStore
        let projectDir = thisFile
            .deletingLastPathComponent() // LibraVaultTests/ -> this file's containing directory
            .deletingLastPathComponent() // the Xcode project directory (parent of LibraVault/ and LibraVaultTests/)
        let sourcesDir = projectDir
            .appendingPathComponent("LibraVault")
            .appendingPathComponent("Sources")
            .appendingPathComponent("VaultStore")
        guard FileManager.default.fileExists(atPath: sourcesDir.path) else {
            throw XCTSkip(
                "Could not locate Sources/VaultStore relative to this test file's #filePath (\(thisFile.path))"
                    + " — probably running outside the Simulator/host filesystem this check depends on."
            )
        }
        return sourcesDir
    }

    func testNoFileUnderVaultStoreReferencesAPlaintextPersistenceType() throws {
        let dir = try vaultStoreSourceDirectory()
        let swiftFiles = try FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "swift" }
        XCTAssertFalse(swiftFiles.isEmpty, "expected at least one Swift file under \(dir.path) to actually check")

        for file in swiftFiles {
            let contents = try String(contentsOf: file, encoding: .utf8)
            for identifier in Self.forbiddenIdentifiers {
                XCTAssertFalse(
                    contents.contains(identifier),
                    "\(file.lastPathComponent) references '\(identifier)' — Sources/VaultStore/ must never touch"
                        + " the app's plaintext persistence/cache layer"
                )
            }
        }
    }
}
