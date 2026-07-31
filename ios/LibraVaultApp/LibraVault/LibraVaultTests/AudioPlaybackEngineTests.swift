import XCTest
import AVFoundation
@testable import LibraVault

/// Deliberately uses `load(fileURL:rate:)`, not `play(fileURL:rate:)`, throughout —
/// `load` prepares the file (real duration, real decode) without starting actual
/// audio output, which is what these assertions need and is safer to run
/// unattended in a headless CI simulator than triggering real playback.
final class AudioPlaybackEngineTests: XCTestCase {
    private var tempDir: URL!

    override func setUpWithError() throws {
        tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("AudioPlaybackEngineTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: tempDir)
    }

    func testLoadThrowsForAnInvalidAudioFile() throws {
        let notAudioURL = tempDir.appendingPathComponent("not-audio.mp3")
        try Data("this is not an audio file".utf8).write(to: notAudioURL)

        let engine = AudioPlaybackEngine()
        XCTAssertThrowsError(try engine.load(fileURL: notAudioURL, rate: 1.0))
    }

    func testLoadThrowsForAMissingFile() throws {
        let missingURL = tempDir.appendingPathComponent("does-not-exist.mp3")

        let engine = AudioPlaybackEngine()
        XCTAssertThrowsError(try engine.load(fileURL: missingURL, rate: 1.0))
    }

    /// A real, valid, silent WAV file written via AVAudioFile — the standard Apple API
    /// for writing PCM audio — so loading is exercised against a genuinely decodable
    /// file rather than a synthetic stand-in.
    private func makeFixtureWAV(seconds: Double) throws -> URL {
        let format = AVAudioFormat(standardFormatWithSampleRate: 44100, channels: 1)!
        let frameCount = AVAudioFrameCount(44100 * seconds)
        let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frameCount)!
        buffer.frameLength = frameCount

        let fileURL = tempDir.appendingPathComponent("fixture-\(UUID().uuidString).wav")
        let audioFile = try AVAudioFile(forWriting: fileURL, settings: format.settings)
        try audioFile.write(from: buffer)
        return fileURL
    }

    func testLoadReportsTheFilesRealDuration() throws {
        let fileURL = try makeFixtureWAV(seconds: 1.0)

        let engine = AudioPlaybackEngine()
        try engine.load(fileURL: fileURL, rate: 1.0)

        XCTAssertEqual(engine.duration, 1.0, accuracy: 0.05)
    }

    func testElapsedIsClampedToDuration() throws {
        let fileURL = try makeFixtureWAV(seconds: 1.0)
        let engine = AudioPlaybackEngine()
        try engine.load(fileURL: fileURL, rate: 1.0)

        engine.elapsed = 1000
        XCTAssertEqual(engine.elapsed, engine.duration, accuracy: 0.05)

        engine.elapsed = -10
        XCTAssertEqual(engine.elapsed, 0, accuracy: 0.05)
    }

    func testStopClearsDuration() throws {
        let fileURL = try makeFixtureWAV(seconds: 1.0)
        let engine = AudioPlaybackEngine()
        try engine.load(fileURL: fileURL, rate: 1.0)
        XCTAssertGreaterThan(engine.duration, 0)

        engine.stop()

        XCTAssertEqual(engine.duration, 0)
        XCTAssertFalse(engine.isPlaying)
    }
}
