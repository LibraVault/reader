import XCTest
@testable import LibraVault

final class UnlockAttemptThrottleTests: XCTestCase {

    func testFirstFewFailuresAreNeverThrottled() {
        for n in 0..<3 {
            XCTAssertFalse(UnlockAttemptThrottle.isThrottled(failedAttempts: n, lastAttemptEpochMillis: 0, nowEpochMillis: 0))
        }
    }

    func testThrottledImmediatelyAfterExceedingTheFreeAttemptThreshold() {
        XCTAssertTrue(UnlockAttemptThrottle.isThrottled(failedAttempts: 4, lastAttemptEpochMillis: 1_000, nowEpochMillis: 1_000))
    }

    func testNoLongerThrottledOnceEnoughTimeHasPassed() {
        let delay = UnlockAttemptThrottle.remainingDelayMillis(failedAttempts: 4, lastAttemptEpochMillis: 0, nowEpochMillis: 0)
        XCTAssertGreaterThan(delay, 0)
        XCTAssertFalse(UnlockAttemptThrottle.isThrottled(failedAttempts: 4, lastAttemptEpochMillis: 0, nowEpochMillis: delay))
    }

    func testDelayIncreasesWithMoreConsecutiveFailures() {
        let d1 = UnlockAttemptThrottle.remainingDelayMillis(failedAttempts: 4, lastAttemptEpochMillis: 0, nowEpochMillis: 0)
        let d2 = UnlockAttemptThrottle.remainingDelayMillis(failedAttempts: 6, lastAttemptEpochMillis: 0, nowEpochMillis: 0)
        let d3 = UnlockAttemptThrottle.remainingDelayMillis(failedAttempts: 10, lastAttemptEpochMillis: 0, nowEpochMillis: 0)
        XCTAssertGreaterThan(d2, d1)
        XCTAssertGreaterThan(d3, d2)
    }

    func testDelayIsCappedDoesNotGrowUnbounded() {
        let d100 = UnlockAttemptThrottle.remainingDelayMillis(failedAttempts: 100, lastAttemptEpochMillis: 0, nowEpochMillis: 0)
        let d1000 = UnlockAttemptThrottle.remainingDelayMillis(failedAttempts: 1000, lastAttemptEpochMillis: 0, nowEpochMillis: 0)
        XCTAssertEqual(d100, d1000)
    }

    func testNeverReturnsANegativeDelay() {
        let delay = UnlockAttemptThrottle.remainingDelayMillis(
            failedAttempts: 10,
            lastAttemptEpochMillis: 0,
            nowEpochMillis: Int64.max / 2
        )
        XCTAssertGreaterThanOrEqual(delay, 0)
    }
}

final class UnlockAttemptThrottleStoreTests: XCTestCase {

    private var vaultDir: URL!

    override func setUpWithError() throws {
        vaultDir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: vaultDir)
    }

    func testReadOnAVaultWithNoThrottleFileYetReturnsInitialState() {
        XCTAssertEqual(UnlockAttemptThrottleStore.read(vaultDir: vaultDir), .initial)
    }

    func testWriteThenReadRoundTrips() throws {
        let state = UnlockThrottleState(failedAttempts: 5, lastAttemptEpochMillis: 1_700_000_000_000)
        try UnlockAttemptThrottleStore.write(vaultDir: vaultDir, state: state)

        XCTAssertEqual(UnlockAttemptThrottleStore.read(vaultDir: vaultDir), state)
    }

    func testWriteSurvivesAcrossASecondReaderInstance() throws {
        try UnlockAttemptThrottleStore.write(
            vaultDir: vaultDir,
            state: UnlockThrottleState(failedAttempts: 2, lastAttemptEpochMillis: 123)
        )

        // Simulates a process restart: nothing but the file on disk carries state.
        let reread = UnlockAttemptThrottleStore.read(vaultDir: vaultDir)
        XCTAssertEqual(reread.failedAttempts, 2)
        XCTAssertEqual(reread.lastAttemptEpochMillis, 123)
    }

    func testLatestWriteWinsOnOverwrite() throws {
        try UnlockAttemptThrottleStore.write(vaultDir: vaultDir, state: UnlockThrottleState(failedAttempts: 1, lastAttemptEpochMillis: 10))
        try UnlockAttemptThrottleStore.write(vaultDir: vaultDir, state: UnlockThrottleState(failedAttempts: 2, lastAttemptEpochMillis: 20))

        XCTAssertEqual(UnlockAttemptThrottleStore.read(vaultDir: vaultDir), UnlockThrottleState(failedAttempts: 2, lastAttemptEpochMillis: 20))
    }

    func testNoStrayTmpFileSurvivesASuccessfulWrite() throws {
        try UnlockAttemptThrottleStore.write(vaultDir: vaultDir, state: UnlockThrottleState(failedAttempts: 1, lastAttemptEpochMillis: 10))

        let contents = try FileManager.default.contentsOfDirectory(atPath: vaultDir.path)
        XCTAssertFalse(contents.contains { $0.hasSuffix(".tmp") })
    }
}
