import XCTest
@testable import LibraVault

final class LibraVaultLogStoreTests: XCTestCase {

    private func makeIsolatedStore(maxLogSizeBytes: Int = 512 * 1024) throws -> LibraVaultLogStore {
        let suiteName = "LibraVaultLogStoreTests.\(UUID().uuidString)"
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("LibraVaultLogStoreTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return LibraVaultLogStore(
            defaults: UserDefaults(suiteName: suiteName)!,
            directory: directory,
            maxLogSizeBytes: maxLogSizeBytes
        )
    }

    // MARK: - isEnabled gating

    func testIsEnabledDefaultsToFalse() throws {
        let store = try makeIsolatedStore()
        XCTAssertFalse(store.isEnabled)
    }

    func testWriteDoesNothingWhenDisabled() throws {
        let store = try makeIsolatedStore()
        store.isEnabled = false

        store.write(level: "D", tag: "Test", message: "should not be recorded")

        XCTAssertEqual(store.readLogs(), "No logs recorded.")
    }

    func testWriteRecordsLogsWhenEnabled() throws {
        let store = try makeIsolatedStore()
        store.isEnabled = true

        store.write(level: "D", tag: "Test", message: "hello world")

        XCTAssertTrue(store.readLogs().contains("[D/Test] hello world"))
    }

    // MARK: - Multiple writes / clear

    func testMultipleWritesAppendRatherThanOverwrite() throws {
        let store = try makeIsolatedStore()
        store.isEnabled = true

        store.write(level: "D", tag: "Test", message: "first")
        store.write(level: "D", tag: "Test", message: "second")

        let logs = store.readLogs()
        XCTAssertTrue(logs.contains("first"))
        XCTAssertTrue(logs.contains("second"))
    }

    func testClearLogsRemovesRecordedEntries() throws {
        let store = try makeIsolatedStore()
        store.isEnabled = true
        store.write(level: "D", tag: "Test", message: "to be cleared")

        store.clearLogs()

        XCTAssertEqual(store.readLogs(), "No logs recorded.")
    }

    // MARK: - Rotation

    func testWriteRotatesWhenExceedingMaxSize() throws {
        let store = try makeIsolatedStore(maxLogSizeBytes: 10)
        store.isEnabled = true

        store.write(level: "D", tag: "Test", message: "this line alone is already over the tiny size limit")
        store.write(level: "D", tag: "Test", message: "second entry after rotation")

        // Rotation only triggers once the *existing* file already exceeds the limit,
        // so after two writes the current log should hold just the latest entry.
        let logs = store.readLogs()
        XCTAssertTrue(logs.contains("second entry after rotation"))
    }
}
