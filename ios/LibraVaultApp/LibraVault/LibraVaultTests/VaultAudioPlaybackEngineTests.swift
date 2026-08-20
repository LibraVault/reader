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
}
