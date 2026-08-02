import XCTest
@testable import LibraVault

/// `Bundle(url:)` only needs a valid directory to answer the file-existence
/// checks `PocketModelManager` makes - it doesn't require a real Info.plist -
/// so a plain temp directory standing in for the app bundle is enough to
/// test the resolution logic without needing a real device/simulator build
/// with the bundled model present.
final class PocketModelManagerTests: XCTestCase {

    private var tempDirectory: URL!

    override func setUp() {
        super.setUp()
        tempDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("PocketModelManagerTests-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: tempDirectory, withIntermediateDirectories: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: tempDirectory)
        tempDirectory = nil
        super.tearDown()
    }

    private func fakeBundle() -> Bundle {
        Bundle(url: tempDirectory)!
    }

    func testModelDirectoryPathIsNilWhenSubdirectoryIsMissing() {
        let manager = PocketModelManager(bundle: fakeBundle(), subdirectory: "PocketTTSModel")
        XCTAssertNil(manager.modelDirectoryPath)
        XCTAssertFalse(manager.isModelAvailable)
    }

    func testModelDirectoryPathIsNilWhenPathExistsButIsAFileNotADirectory() {
        let modelPath = tempDirectory.appendingPathComponent("PocketTTSModel")
        FileManager.default.createFile(atPath: modelPath.path, contents: Data())

        let manager = PocketModelManager(bundle: fakeBundle(), subdirectory: "PocketTTSModel")

        XCTAssertNil(manager.modelDirectoryPath)
        XCTAssertFalse(manager.isModelAvailable)
    }

    func testModelDirectoryPathResolvesWhenSubdirectoryExists() throws {
        let modelDir = tempDirectory.appendingPathComponent("PocketTTSModel")
        try FileManager.default.createDirectory(at: modelDir, withIntermediateDirectories: true)

        let manager = PocketModelManager(bundle: fakeBundle(), subdirectory: "PocketTTSModel")

        XCTAssertEqual(manager.modelDirectoryPath, modelDir.path)
        XCTAssertTrue(manager.isModelAvailable)
    }

    func testCustomSubdirectoryNameIsRespected() throws {
        let modelDir = tempDirectory.appendingPathComponent("SomeOtherFolder")
        try FileManager.default.createDirectory(at: modelDir, withIntermediateDirectories: true)

        let manager = PocketModelManager(bundle: fakeBundle(), subdirectory: "SomeOtherFolder")

        XCTAssertEqual(manager.modelDirectoryPath, modelDir.path)
    }
}
