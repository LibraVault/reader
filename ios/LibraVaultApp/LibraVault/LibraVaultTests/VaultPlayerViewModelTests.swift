import XCTest
import AVFoundation
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

    /// A real, valid, silent WAV file — same fixture-building approach as
    /// `AudioPlaybackEngineTests`. Imported under the `"mp3"` format string
    /// deliberately: `VaultManifestEntry.format` only decides *routing*
    /// (`VaultContentFormat.isAudio`) here, never what `AVAudioPlayer(data:)`
    /// actually decodes — it sniffs the real container from the bytes
    /// themselves (see `VaultAudioPlaybackEngine`'s own doc comment on why
    /// it never passes a `fileTypeHint`), so a genuine WAV byte stream loads
    /// correctly regardless of the declared format string.
    private func importFixtureAudio(into manager: VaultSessionManager, vaultId: String, seconds: Double = 1.0, title: String = "A Track") async throws -> Data {
        let format = AVAudioFormat(standardFormatWithSampleRate: 44100, channels: 1)!
        let frameCount = AVAudioFrameCount(44100 * seconds)
        let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frameCount)!
        buffer.frameLength = frameCount

        let fileURL = FileManager.default.temporaryDirectory.appendingPathComponent("player-vm-fixture-\(UUID().uuidString).wav")
        let audioFile = try AVAudioFile(forWriting: fileURL, settings: format.settings)
        try audioFile.write(from: buffer)
        let data = try Data(contentsOf: fileURL)

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
