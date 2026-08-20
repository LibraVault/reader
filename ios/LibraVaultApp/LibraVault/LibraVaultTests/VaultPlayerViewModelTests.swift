import XCTest
@testable import LibraVault

@MainActor
final class VaultPlayerViewModelTests: XCTestCase {

    private func makeUnlockedVault() async throws -> (manager: VaultSessionManager, id: String) {
        let rootDir = FileManager.default.temporaryDirectory.appendingPathComponent("player-vm-test-\(UUID().uuidString)")
        let manager = VaultSessionManager(rootDir: rootDir, keyWrapFactory: FakeHardwareKeyWrapFactory())
        let result = try await manager.createVault(displayName: "Personal", pin: Array("1234".utf8))
        guard case .success(let id, _) = result else { XCTFail("expected .success"); return (manager, "") }
        return (manager, id)
    }

    /// A real, valid, silent WAV — hand-written classic 16-bit PCM, byte by
    /// byte; see `VaultAudioPlaybackEngineTests.makeFixtureWAVData`'s doc
    /// comment for why (in short: `AVAudioFile`/`AVAudioFormat.standardFormat`
    /// writes 32-bit float PCM, which `AVAudioPlayer(data:fileTypeHint:)`
    /// reported `duration` `0` for in CI even with an explicit `.wav` hint).
    /// Imported under the `"mp3"` format string deliberately:
    /// `VaultManifestEntry.format` only decides *routing*
    /// (`VaultContentFormat.isAudio`) here, never what
    /// `AVAudioPlayer(data:fileTypeHint:)` actually decodes —
    /// `VaultAudioPlaybackEngine.fileTypeHint(for:)` sniffs the real
    /// container from the bytes themselves, so a genuine WAV byte stream
    /// loads correctly (and gets the correct `.wav` hint) regardless of the
    /// declared format string.
    private func makeFixtureWAVData(seconds: Double, sampleRate: UInt32 = 44100) -> Data {
        let bitsPerSample: UInt16 = 16
        let channelCount: UInt16 = 1
        let sampleCount = Int(Double(sampleRate) * seconds)
        let byteRate = sampleRate * UInt32(channelCount) * UInt32(bitsPerSample / 8)
        let blockAlign = channelCount * (bitsPerSample / 8)
        let dataSize = UInt32(sampleCount * Int(channelCount) * Int(bitsPerSample / 8))

        var data = Data()
        func appendASCII(_ s: String) { data.append(contentsOf: s.utf8) }
        func appendUInt32(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { data.append(contentsOf: $0) } }
        func appendUInt16(_ v: UInt16) { withUnsafeBytes(of: v.littleEndian) { data.append(contentsOf: $0) } }

        appendASCII("RIFF")
        appendUInt32(36 + dataSize)
        appendASCII("WAVE")
        appendASCII("fmt ")
        appendUInt32(16)
        appendUInt16(1) // PCM
        appendUInt16(channelCount)
        appendUInt32(sampleRate)
        appendUInt32(byteRate)
        appendUInt16(blockAlign)
        appendUInt16(bitsPerSample)
        appendASCII("data")
        appendUInt32(dataSize)
        data.append(Data(count: Int(dataSize)))

        return data
    }

    private func importFixtureAudio(into manager: VaultSessionManager, vaultId: String, seconds: Double = 1.0, title: String = "A Track") async throws -> Data {
        let data = makeFixtureWAVData(seconds: seconds)

        let store = await manager.requireUnlocked(vaultId)
        let input = InputStream(data: data)
        input.open()
        let entry = try store.importFile(input: input, declaredSize: Int64(data.count), title: title, author: nil, format: "mp3")
        input.close()
        return entry.fileId
    }

    func testLoadPlaysTheDecryptedAudioAndReportsItsDuration() async throws {
        let (manager, id) = try await makeUnlockedVault()
        let fileId = try await importFixtureAudio(into: manager, vaultId: id, seconds: 1.0)

        let vm = VaultPlayerViewModel(vaultId: id, fileId: fileId, sessionManager: manager)
        await vm.load()

        XCTAssertFalse(vm.isLoading)
        XCTAssertNil(vm.errorMessage)
        XCTAssertEqual(vm.title, "A Track")
        XCTAssertEqual(vm.duration, 1.0, accuracy: 0.05)
        vm.stop()
    }

    func testLoadOnALockedVaultReportsAnError() async throws {
        let (manager, id) = try await makeUnlockedVault()
        let fileId = try await importFixtureAudio(into: manager, vaultId: id)
        await manager.lock(id)

        let vm = VaultPlayerViewModel(vaultId: id, fileId: fileId, sessionManager: manager)
        await vm.load()

        XCTAssertEqual(vm.errorMessage, "Vault is locked")
        XCTAssertFalse(vm.isLoading)
    }

    func testLoadForANonAudioEntryReportsAnError() async throws {
        let (manager, id) = try await makeUnlockedVault()
        let store = await manager.requireUnlocked(id)
        let input = InputStream(data: Data(count: 10))
        input.open()
        let entry = try store.importFile(input: input, declaredSize: 10, title: "Doc", author: nil, format: "pdf")
        input.close()

        let vm = VaultPlayerViewModel(vaultId: id, fileId: entry.fileId, sessionManager: manager)
        await vm.load()

        XCTAssertEqual(vm.errorMessage, "Not an audio file")
    }

    /// Round-trips a bookmark through the encrypted manifest, same
    /// acceptance criterion `VaultReaderViewModelTests` checks for
    /// EPUB/PDF — reading it back via a second view model instance, not
    /// just the in-memory one that created it.
    func testAddBookmarkUsesTheMsPositionRefConventionAndPersists() async throws {
        let (manager, id) = try await makeUnlockedVault()
        let fileId = try await importFixtureAudio(into: manager, vaultId: id, seconds: 2.0)
        let vm = VaultPlayerViewModel(vaultId: id, fileId: fileId, sessionManager: manager)
        await vm.load()
        vm.onSeek(to: 0.5)

        await vm.addBookmark(label: "Interesting bit")

        XCTAssertEqual(vm.bookmarks.count, 1)
        XCTAssertTrue(vm.bookmarks.first?.positionRef.hasPrefix("ms:") ?? false)
        XCTAssertEqual(vm.bookmarks.first?.label, "Interesting bit")
        vm.stop()

        let vm2 = VaultPlayerViewModel(vaultId: id, fileId: fileId, sessionManager: manager)
        await vm2.load()
        XCTAssertEqual(vm2.bookmarks.count, 1)
        vm2.stop()
    }

    func testOnSeekClampsToDurationAndZero() async throws {
        let (manager, id) = try await makeUnlockedVault()
        let fileId = try await importFixtureAudio(into: manager, vaultId: id, seconds: 1.0)
        let vm = VaultPlayerViewModel(vaultId: id, fileId: fileId, sessionManager: manager)
        await vm.load()

        vm.onSeek(to: 1000)
        XCTAssertEqual(vm.elapsed, vm.duration, accuracy: 0.05)

        vm.onSeek(to: -10)
        XCTAssertEqual(vm.elapsed, 0, accuracy: 0.05)
        vm.stop()
    }
}
