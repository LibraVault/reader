import XCTest
@testable import LibraVault

/// Mirrors Android's `VaultStoreTest.kt` case-by-case.
final class VaultStoreTests: XCTestCase {

    // Small params - correctness tests, not latency benchmarks.
    private let fastParams = Argon2Params(memoryKiB: 8 * 1024, iterations: 1, parallelism: 1)

    private func newStore(
        keyWrapFactory: FakeHardwareKeyWrapFactory = FakeHardwareKeyWrapFactory(),
        now: @escaping () -> Int64 = { 0 },
        usableSpace: (() -> Int64)? = nil,
        newFileId: (() throws -> Data)? = nil
    ) -> VaultStore {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("vaultstore-test-\(UUID().uuidString)")
        return VaultStore(
            vaultDir: dir, keystoreKeyAlias: "test-vault-alias", keyWrapFactory: keyWrapFactory,
            nowEpochMillis: now, usableSpaceBytes: usableSpace, newFileId: newFileId
        )
    }

    func testCreateLeavesTheVaultUnlockedAndReturnsAUsableRecoveryKey() throws {
        let store = newStore()
        let recoveryKey = try store.create(pin: pin("1234"), argon2Params: fastParams)

        XCTAssertTrue(store.isUnlocked)
        XCTAssertTrue(store.exists())
        XCTAssertEqual(recoveryKey.count, 32)
    }

    func testCreateTwiceOnTheSameDirectoryFails() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)

        XCTAssertThrowsError(try store.create(pin: pin("5678"), argon2Params: fastParams)) { error in
            XCTAssertEqual(error as? VaultStoreError, .vaultAlreadyExists)
        }
    }

    func testCreateCleansUpTheDirectoryIfHardwareIsUnavailable() {
        let factory = FakeHardwareKeyWrapFactory()
        factory.simulateHardwareUnavailable = true
        let store = newStore(keyWrapFactory: factory)

        XCTAssertThrowsError(try store.create(pin: pin("1234"), argon2Params: fastParams)) { error in
            guard case .secureEnclaveUnavailable = error as? HardwareKeyWrapError else {
                XCTFail("expected .secureEnclaveUnavailable, got \(error)")
                return
            }
        }
        XCTAssertFalse(store.exists(), "a failed create() must not leave a half-created vault behind")
    }

    func testLockThenUnlockWithTheCorrectPinSucceeds() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        store.lock()
        XCTAssertFalse(store.isUnlocked)

        XCTAssertEqual(try store.unlockWithPin(pin("1234")), .success)
        XCTAssertTrue(store.isUnlocked)
    }

    func testUnlockWithTheWrongPinFailsAndDoesNotUnlock() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        store.lock()

        XCTAssertEqual(try store.unlockWithPin(pin("9999")), .wrongCredential)
        XCTAssertFalse(store.isUnlocked)
    }

    func testRepeatedWrongPinsEventuallyThrottle() throws {
        var clock: Int64 = 0
        let store = newStore(now: { clock })
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        store.lock()

        var lastOutcome: UnlockOutcome?
        for _ in 0..<10 {
            lastOutcome = try store.unlockWithPin(pin("9999"))
            clock += 1 // attempts happen "instantly" in test time, so throttling must kick in
        }
        guard case .throttled = lastOutcome else {
            XCTFail("expected throttling after repeated failures, got \(String(describing: lastOutcome))")
            return
        }
    }

    func testASuccessfulUnlockResetsTheFailureCount() throws {
        var clock: Int64 = 0
        let store = newStore(now: { clock })
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        store.lock()

        for _ in 0..<3 {
            _ = try store.unlockWithPin(pin("9999"))
            clock += 1
        }
        clock += 10_000 // clear of any throttle window
        XCTAssertEqual(try store.unlockWithPin(pin("1234")), .success)

        store.lock()
        // Immediately wrong again - if the counter had NOT reset, this could already be throttled.
        XCTAssertEqual(try store.unlockWithPin(pin("0000")), .wrongCredential)
    }

    func testRecoveryKeyUnlocksIndependentlyOfThePin() throws {
        let store = newStore()
        let recoveryKey = try store.create(pin: pin("1234"), argon2Params: fastParams)
        store.lock()

        XCTAssertEqual(try store.unlockWithRecoveryKey(recoveryKey), .success)
        XCTAssertTrue(store.isUnlocked)
    }

    func testRecoveryKeyStillUnlocksAfterTheKeystoreKeyIsLost() throws {
        // This is the entire justification for the recovery key existing —
        // the scenario a lost Secure Enclave key is meant to be rescued by.
        let factory = FakeHardwareKeyWrapFactory()
        let store = newStore(keyWrapFactory: factory)
        let recoveryKey = try store.create(pin: pin("1234"), argon2Params: fastParams)
        store.lock()

        factory.forgetKey("test-vault-alias")

        XCTAssertEqual(try store.unlockWithRecoveryKey(recoveryKey), .success)
    }

    func testPinUnlockReportsKeystoreKeyLostNotAGenericFailureWhenTheKeystoreKeyIsGone() throws {
        let factory = FakeHardwareKeyWrapFactory()
        let store = newStore(keyWrapFactory: factory)
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        store.lock()

        factory.forgetKey("test-vault-alias")

        XCTAssertEqual(try store.unlockWithPin(pin("1234")), .keystoreKeyLost)
    }

    func testWrongRecoveryKeyFails() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        store.lock()

        XCTAssertEqual(try store.unlockWithRecoveryKey(Data(repeating: 0, count: 32)), .wrongCredential)
    }

    func testImportFileWhileLockedThrows() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        store.lock()

        XCTAssertThrowsError(
            try store.importFile(input: InputStream(data: Data(count: 10)), declaredSize: 10, title: "title", author: nil, format: "pdf")
        ) { error in
            XCTAssertEqual(error as? VaultStoreError, .vaultLocked)
        }
    }

    func testImportedFileRoundTripsThroughTheManifestAndContentReader() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)

        let content = VaultCryptoTestSupport.randomData(100_000)
        let input = InputStream(data: content)
        input.open()
        let entry = try store.importFile(input: input, declaredSize: Int64(content.count), title: "My Document", author: "Some Author", format: "pdf")
        input.close()

        let entries = try store.listEntries()
        XCTAssertEqual(entries.count, 1)
        XCTAssertEqual(entries[0], entry)
        XCTAssertEqual(entries[0].title, "My Document")

        let reader = try store.openReader(fileId: entry.fileId)
        defer { reader.close() }
        XCTAssertEqual(try reader.readAt(offset: 0, length: content.count), content)
    }

    func testManifestSurvivesALockUnlockCycle() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)
        let input = InputStream(data: Data(count: 10))
        input.open()
        _ = try store.importFile(input: input, declaredSize: 10, title: "Title", author: nil, format: "pdf")
        input.close()

        store.lock()
        _ = try store.unlockWithPin(pin("1234"))

        XCTAssertEqual(try store.listEntries().count, 1)
    }

    func testInsufficientStorageIsRejectedBeforeAnyBytesAreWritten() throws {
        let store = newStore(usableSpace: { 100 }) // far less than what's about to be requested
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)

        let input = InputStream(data: Data(count: 1_000_000))
        input.open()
        XCTAssertThrowsError(
            try store.importFile(input: input, declaredSize: 1_000_000, title: "title", author: nil, format: "pdf")
        ) { error in
            guard case .insufficientStorage = error as? VaultStoreError else {
                XCTFail("expected .insufficientStorage, got \(error)")
                return
            }
        }
        input.close()
        XCTAssertTrue(try store.listEntries().isEmpty, "a rejected import must not appear in the manifest")
    }

    func testTwoImportedFilesGetDistinctFileIds() throws {
        let store = newStore()
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)

        let input1 = InputStream(data: Data(count: 10))
        input1.open()
        let e1 = try store.importFile(input: input1, declaredSize: 10, title: "A", author: nil, format: "pdf")
        input1.close()

        let input2 = InputStream(data: Data(count: 10))
        input2.open()
        let e2 = try store.importFile(input: input2, declaredSize: 10, title: "B", author: nil, format: "pdf")
        input2.close()

        XCTAssertNotEqual(e1.fileId, e2.fileId)
        XCTAssertEqual(try store.listEntries().count, 2)
    }

    /// Regression test for the ordering bug Android's own review caught: an
    /// earlier draft of `importFile` updated the manifest BEFORE renaming
    /// the temp file into place, so a failure between those two steps left
    /// the manifest pointing at a file that didn't exist yet. Forces the
    /// finalize-rename itself to fail (by pre-occupying the target path
    /// with a directory, via the injectable `newFileId` generator predicting
    /// exactly which path `importFile` will pick) and asserts the manifest
    /// was never touched — proving the fix's ordering, not just its happy path.
    func testAFailedRenameDuringImportLeavesNoManifestEntryBehind() throws {
        let predictedFileId = Data(repeating: 0x42, count: 16)
        let store = newStore(newFileId: { predictedFileId })
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)

        // Occupy the target path so the finalize-rename must fail.
        try FileManager.default.createDirectory(at: store.contentFile(fileId: predictedFileId), withIntermediateDirectories: true)

        let input = InputStream(data: Data(count: 10))
        input.open()
        XCTAssertThrowsError(
            try store.importFile(input: input, declaredSize: 10, title: "title", author: nil, format: "pdf")
        ) { error in
            guard case .internalError = error as? VaultStoreError else {
                XCTFail("expected .internalError, got \(error)")
                return
            }
        }
        input.close()
        XCTAssertTrue(try store.listEntries().isEmpty, "a failed rename must not leave a manifest entry behind")
    }

    /// Sibling of `testAFailedRenameDuringImportLeavesNoManifestEntryBehind`
    /// for `setCoverArt`, which has the identical
    /// encrypt-to-temp/rename/update-manifest structure and identical
    /// cleanup-on-failure logic — proves the same crash-safety property
    /// holds here too, not just for the original import path.
    func testAFailedRenameDuringSetCoverArtLeavesTheManifestAndOldCoverUntouched() throws {
        let predictedCoverFileId = Data(repeating: 0x43, count: 16)
        var nextFileId = false
        let store = newStore(newFileId: {
            defer { nextFileId.toggle() }
            // First call is the imported file's own fileId (real random is
            // fine there); the second is setCoverArt's new cover fileId,
            // which must be the predictable one so its target path can be
            // pre-occupied below.
            return nextFileId ? predictedCoverFileId : Data(repeating: 0x99, count: 16)
        })
        _ = try store.create(pin: pin("1234"), argon2Params: fastParams)

        let input = InputStream(data: Data(count: 10))
        input.open()
        let entry = try store.importFile(input: input, declaredSize: 10, title: "title", author: nil, format: "pdf")
        input.close()
        XCTAssertNil(entry.coverArtFileId, "sanity check: imported with no cover art yet")

        // Occupy the target path so setCoverArt's finalize-rename must fail.
        try FileManager.default.createDirectory(at: store.contentFile(fileId: predictedCoverFileId), withIntermediateDirectories: true)

        XCTAssertThrowsError(try store.setCoverArt(fileId: entry.fileId, jpegBytes: Data(count: 10))) { error in
            guard case .internalError = error as? VaultStoreError else {
                XCTFail("expected .internalError, got \(error)")
                return
            }
        }
        XCTAssertNil(try store.listEntries()[0].coverArtFileId, "a failed setCoverArt must not leave the manifest pointing at the new (nonexistent) cover")
    }

    private func pin(_ s: String) -> [UInt8] { Array(s.utf8) }
}
