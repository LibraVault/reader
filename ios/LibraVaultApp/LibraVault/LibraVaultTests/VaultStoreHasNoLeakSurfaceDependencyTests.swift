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
            let code = Self.stripLineComments(from: contents)
            for identifier in Self.forbiddenIdentifiers {
                XCTAssertFalse(
                    code.contains(identifier),
                    "\(file.lastPathComponent) references '\(identifier)' — Sources/VaultStore/ must never touch"
                        + " the app's plaintext persistence/cache layer"
                )
            }
        }
    }

    /// Confirms `stripLineComments` fixes the false positive (a doc comment
    /// merely naming a forbidden type) without opening a false negative (an
    /// actual code reference on its own line still survives the strip and
    /// would still fail `testNoFileUnderVaultStoreReferencesAPlaintextPersistenceType`).
    func testStripLineCommentsDropsCommentMentionButKeepsRealCodeReference() {
        let source = """
            /// Callers must pre-process bytes through `CoverArtCache` first.
            let cache = CoverArtCache.shared
            """
        let stripped = Self.stripLineComments(from: source)
        XCTAssertFalse(stripped.contains("Callers must"), "doc comment line must be dropped entirely")
        XCTAssertFalse(stripped.contains("first"), "doc comment line must be dropped entirely")
        XCTAssertTrue(stripped.contains("CoverArtCache.shared"), "a real code reference must still be detectable")
    }

    /// Drops the `//`/`///` comment portion of every line so doc comments
    /// merely *naming* a forbidden type (e.g. explaining a caller-side
    /// boundary) don't trip the scan — only an actual reference in code
    /// should. Deliberately line-based, not a full Swift tokenizer: this
    /// codebase's convention (confirmed above) is exclusively `//`-style
    /// comments under `Sources/VaultStore/`, no `/* */` block comments, so a
    /// per-line split covers every real case without the complexity of a
    /// real lexer.
    private static func stripLineComments(from source: String) -> String {
        source
            .split(separator: "\n", omittingEmptySubsequences: false)
            .map { line -> Substring in
                guard let commentRange = line.range(of: "//") else { return line }
                return line[line.startIndex..<commentRange.lowerBound]
            }
            .joined(separator: "\n")
    }
}
