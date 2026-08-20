import XCTest
import AVFoundation
@testable import LibraVault

/// Mirrors `AudioPlaybackEngineTests`' own scope and reasoning — deliberately
/// uses `load(data:rate:)`, never `play(data:rate:)`, so nothing here
/// triggers real `AVAudioPlayer` output, which that file's doc comment notes
/// is unsafe to run unattended in the CI Simulator.
final class VaultAudioPlaybackEngineTests: XCTestCase {

    func testLoadThrowsForDataThatIsNotAnAudioFile() {
        let notAudio = Data("this is not an audio file".utf8)

        let engine = VaultAudioPlaybackEngine()
        XCTAssertThrowsError(try engine.load(data: notAudio, rate: 1.0))
    }

    /// A real, valid, silent WAV — same fixture-building approach as
    /// `AudioPlaybackEngineTests.makeFixtureWAV`, read back as `Data` since
    /// this engine loads from memory, not a file URL.
    private func makeFixtureWAVData(seconds: Double) throws -> Data {
        let format = AVAudioFormat(standardFormatWithSampleRate: 44100, channels: 1)!
        let frameCount = AVAudioFrameCount(44100 * seconds)
        let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frameCount)!
        buffer.frameLength = frameCount

        let fileURL = FileManager.default.temporaryDirectory.appendingPathComponent("vault-audio-fixture-\(UUID().uuidString).wav")
        let audioFile = try AVAudioFile(forWriting: fileURL, settings: format.settings)
        try audioFile.write(from: buffer)
        defer { try? FileManager.default.removeItem(at: fileURL) }
        return try Data(contentsOf: fileURL)
    }

    func testLoadReportsTheDatasRealDuration() throws {
        let data = try makeFixtureWAVData(seconds: 1.0)

        let engine = VaultAudioPlaybackEngine()
        try engine.load(data: data, rate: 1.0)

        XCTAssertEqual(engine.duration, 1.0, accuracy: 0.05)
    }

    func testElapsedIsClampedToDuration() throws {
        let data = try makeFixtureWAVData(seconds: 1.0)
        let engine = VaultAudioPlaybackEngine()
        try engine.load(data: data, rate: 1.0)

        engine.elapsed = 1000
        XCTAssertEqual(engine.elapsed, engine.duration, accuracy: 0.05)

        engine.elapsed = -10
        XCTAssertEqual(engine.elapsed, 0, accuracy: 0.05)
    }

    func testStopClearsDuration() throws {
        let data = try makeFixtureWAVData(seconds: 1.0)
        let engine = VaultAudioPlaybackEngine()
        try engine.load(data: data, rate: 1.0)
        XCTAssertGreaterThan(engine.duration, 0)

        engine.stop()

        XCTAssertEqual(engine.duration, 0)
        XCTAssertFalse(engine.isPlaying)
    }

    // MARK: - fileTypeHint(for:)
    //
    // Regression coverage for the actual bug this file's tests caught in CI:
    // AVAudioPlayer(data:) without an explicit fileTypeHint silently reports
    // duration 0 instead of throwing — see VaultAudioPlaybackEngine's own
    // doc comment. testLoadReportsTheDatasRealDuration above already proves
    // the WAV branch end-to-end; these exercise the remaining container
    // branches directly against small synthetic headers, since building a
    // real fixture file per format (mp3/m4a/flac encoders) is unnecessary
    // ceremony for what's fundamentally a magic-byte lookup.

    func testFileTypeHintRecognizesWAV() {
        var bytes = [UInt8](repeating: 0, count: 12)
        bytes[0...3] = [0x52, 0x49, 0x46, 0x46] // "RIFF"
        bytes[8...11] = [0x57, 0x41, 0x56, 0x45] // "WAVE"
        XCTAssertEqual(VaultAudioPlaybackEngine.fileTypeHint(for: Data(bytes)), AVFileType.wav.rawValue)
    }

    func testFileTypeHintRecognizesFLAC() {
        let bytes: [UInt8] = [0x66, 0x4C, 0x61, 0x43] + [UInt8](repeating: 0, count: 8) // "fLaC"
        XCTAssertEqual(VaultAudioPlaybackEngine.fileTypeHint(for: Data(bytes)), AVFileType.flac.rawValue)
    }

    func testFileTypeHintRecognizesM4A() {
        var bytes = [UInt8](repeating: 0, count: 12)
        bytes[4...7] = [0x66, 0x74, 0x79, 0x70] // "ftyp" at offset 4
        XCTAssertEqual(VaultAudioPlaybackEngine.fileTypeHint(for: Data(bytes)), AVFileType.m4a.rawValue)
    }

    func testFileTypeHintRecognizesMP3WithID3Tag() {
        let bytes: [UInt8] = [0x49, 0x44, 0x33] + [UInt8](repeating: 0, count: 9) // "ID3"
        XCTAssertEqual(VaultAudioPlaybackEngine.fileTypeHint(for: Data(bytes)), AVFileType.mp3.rawValue)
    }

    func testFileTypeHintRecognizesMP3WithRawFrameSync() {
        var bytes = [UInt8](repeating: 0, count: 12)
        bytes[0] = 0xFF
        bytes[1] = 0xFB // 11 set sync bits + MPEG-1 Layer III
        XCTAssertEqual(VaultAudioPlaybackEngine.fileTypeHint(for: Data(bytes)), AVFileType.mp3.rawValue)
    }

    func testFileTypeHintReturnsNilForUnrecognizedData() {
        XCTAssertNil(VaultAudioPlaybackEngine.fileTypeHint(for: Data("not an audio header".utf8)))
    }

    func testFileTypeHintReturnsNilForDataShorterThanTwelveBytes() {
        XCTAssertNil(VaultAudioPlaybackEngine.fileTypeHint(for: Data([0x52, 0x49, 0x46, 0x46])))
    }
}
