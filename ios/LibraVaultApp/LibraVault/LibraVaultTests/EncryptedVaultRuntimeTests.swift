import XCTest
@testable import LibraVault

/// `VaultSessionManager`'s and `VaultForegroundLockObserver`'s own logic is
/// already covered by their dedicated test suites — this only checks that
/// `EncryptedVaultRuntime` actually wires a usable `sessionManager` together
/// with the injected `rootDir`/`keyWrapFactory`, since #202 found nothing in
/// the app actually constructed one until this type existed.
@MainActor
final class EncryptedVaultRuntimeTests: XCTestCase {

    func testSessionManagerIsUsableAgainstTheInjectedRootDirAndFactory() async throws {
        let rootDir = FileManager.default.temporaryDirectory.appendingPathComponent("runtime-test-\(UUID().uuidString)")
        let runtime = EncryptedVaultRuntime(
            rootDir: rootDir,
            keyWrapFactory: FakeHardwareKeyWrapFactory(),
            observeForegroundLock: false
        )

        let result = try await runtime.sessionManager.createVault(displayName: "Personal", pin: Array("1234".utf8))
        guard case .success(let id, _) = result else {
            XCTFail("expected .success")
            return
        }
        let listed = await runtime.sessionManager.listVaults()
        XCTAssertEqual(listed.map(\.id), [id])

        // The vault directory really landed under the injected rootDir, not
        // some other default location.
        XCTAssertTrue(FileManager.default.fileExists(atPath: rootDir.appendingPathComponent(id).path))
    }

    func testDefaultRootDirLivesUnderApplicationSupportSeparateFromEverythingElse() {
        // A directory name check, not a behavioral one: confirms Encrypted
        // Vault content is scoped to its own subdirectory rather than
        // sharing a folder with Folder-scanning roots or
        // ReadingDataPersistence's UserDefaults storage — see this type's
        // own doc comment on `rootDir`.
        XCTAssertEqual(EncryptedVaultRuntime.defaultRootDir.lastPathComponent, "EncryptedVaults")
    }
}
