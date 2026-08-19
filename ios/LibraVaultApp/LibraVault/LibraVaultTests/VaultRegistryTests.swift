import XCTest
@testable import LibraVault

final class VaultRegistryTests: XCTestCase {

    private var baseDir: URL!

    override func setUpWithError() throws {
        baseDir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: baseDir)
    }

    func testListOnFreshDirectoryIsEmptyNoFileCreated() {
        XCTAssertEqual(VaultRegistry.list(baseDir: baseDir), [])
        XCTAssertFalse(FileManager.default.fileExists(atPath: baseDir.path))
    }

    func testAddThenListRoundTrips() throws {
        let entry = VaultRegistryEntry(id: "abc123", displayName: "Personal", createdAtEpochMillis: 42)
        try VaultRegistry.add(baseDir: baseDir, entry: entry)

        XCTAssertEqual(VaultRegistry.list(baseDir: baseDir), [entry])
    }

    func testAddPreservesInsertionOrderAcrossMultipleEntries() throws {
        let first = VaultRegistryEntry(id: "id-1", displayName: "First", createdAtEpochMillis: 1)
        let second = VaultRegistryEntry(id: "id-2", displayName: "Second", createdAtEpochMillis: 2)
        try VaultRegistry.add(baseDir: baseDir, entry: first)
        try VaultRegistry.add(baseDir: baseDir, entry: second)

        XCTAssertEqual(VaultRegistry.list(baseDir: baseDir), [first, second])
    }

    func testAddRejectsADuplicateId() throws {
        let entry = VaultRegistryEntry(id: "dup", displayName: "One", createdAtEpochMillis: 1)
        try VaultRegistry.add(baseDir: baseDir, entry: entry)

        XCTAssertThrowsError(try VaultRegistry.add(baseDir: baseDir, entry: entry.copyRenamed("Two"))) { error in
            XCTAssertEqual(error as? VaultRegistryError, .duplicateId("dup"))
        }
        // The rejected write must not have clobbered the existing entry.
        XCTAssertEqual(VaultRegistry.list(baseDir: baseDir), [entry])
    }

    func testRemoveDropsOnlyTheMatchingId() throws {
        let keep = VaultRegistryEntry(id: "keep", displayName: "Keep me", createdAtEpochMillis: 1)
        let drop = VaultRegistryEntry(id: "drop", displayName: "Drop me", createdAtEpochMillis: 2)
        try VaultRegistry.add(baseDir: baseDir, entry: keep)
        try VaultRegistry.add(baseDir: baseDir, entry: drop)

        try VaultRegistry.remove(baseDir: baseDir, id: "drop")

        XCTAssertEqual(VaultRegistry.list(baseDir: baseDir), [keep])
    }

    func testRemoveOfAnUnknownIdIsANoOp() throws {
        let entry = VaultRegistryEntry(id: "id", displayName: "Name", createdAtEpochMillis: 1)
        try VaultRegistry.add(baseDir: baseDir, entry: entry)

        try VaultRegistry.remove(baseDir: baseDir, id: "does-not-exist")

        XCTAssertEqual(VaultRegistry.list(baseDir: baseDir), [entry])
    }

    func testRenameUpdatesDisplayNameWithoutTouchingIdOrCreatedAt() throws {
        let entry = VaultRegistryEntry(id: "id", displayName: "Old name", createdAtEpochMillis: 7)
        try VaultRegistry.add(baseDir: baseDir, entry: entry)

        try VaultRegistry.rename(baseDir: baseDir, id: "id", newDisplayName: "New name")

        XCTAssertEqual(VaultRegistry.list(baseDir: baseDir), [entry.copyRenamed("New name")])
    }

    func testVaultDirIsASubdirectoryNamedAfterTheIdNotCreatedEagerly() {
        let dir = VaultRegistry.vaultDir(baseDir: baseDir, id: "some-id")

        XCTAssertEqual(dir.standardizedFileURL, baseDir.appendingPathComponent("some-id", isDirectory: true).standardizedFileURL)
        XCTAssertFalse(FileManager.default.fileExists(atPath: dir.path))
    }

    func testNoStrayTmpFileSurvivesASuccessfulWrite() throws {
        try VaultRegistry.add(baseDir: baseDir, entry: VaultRegistryEntry(id: "id", displayName: "Name", createdAtEpochMillis: 1))

        let contents = try FileManager.default.contentsOfDirectory(atPath: baseDir.path)
        XCTAssertFalse(contents.contains { $0.hasSuffix(".tmp") })
    }
}

private extension VaultRegistryEntry {
    func copyRenamed(_ newDisplayName: String) -> VaultRegistryEntry {
        VaultRegistryEntry(id: id, displayName: newDisplayName, createdAtEpochMillis: createdAtEpochMillis)
    }
}
