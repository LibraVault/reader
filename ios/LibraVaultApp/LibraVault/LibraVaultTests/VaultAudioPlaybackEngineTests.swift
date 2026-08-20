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

    /// A real, valid, silent WAV — hand-written classic 16-bit PCM, byte by
    /// byte, deliberately *not* built via `AVAudioFile`/
    /// `AVAudioFormat.standardFormat` (which produces 32-bit float PCM).
    /// That float variant is what this fixture originally used: it decodes
    /// fine through `AVAudioPlayer(contentsOf:)` (a real file URL, what the
    /// non-vault `AudioPlaybackEngineTests` exercises), but
    /// `AVAudioPlayer(data:fileTypeHint:)` reported `duration` as `0` for
    /// it in CI — even with an explicit `.wav` hint (workflow run
    /// 32392013297). Classic 16-bit PCM is the most universally-decodable
    /// WAV variant there is, and writing it by hand removes any dependency
    /// on `AVAudioFile`'s own write-format behavior from the equation
    /// entirely.
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
        appendUInt32(16) // fmt chunk size for classic PCM
        appendUInt16(1) // format tag 1 = PCM
        appendUInt16(channelCount)
        appendUInt32(sampleRate)
        appendUInt32(byteRate)
        appendUInt16(blockAlign)
        appendUInt16(bitsPerSample)
        appendASCII("data")
        appendUInt32(dataSize)
        data.append(Data(count: Int(dataSize))) // silence — all-zero samples

        return data
    }

    func testLoadReportsTheDatasRealDuration() throws {
        let data = makeFixtureWAVData(seconds: 1.0)

        let engine = VaultAudioPlaybackEngine()
        try engine.load(data: data, rate: 1.0)

        XCTAssertEqual(engine.duration, 1.0, accuracy: 0.05)
    }

    func testElapsedIsClampedToDuration() throws {
        let data = makeFixtureWAVData(seconds: 1.0)
        let engine = VaultAudioPlaybackEngine()
        try engine.load(data: data, rate: 1.0)

        engine.elapsed = 1000
        XCTAssertEqual(engine.elapsed, engine.duration, accuracy: 0.05)

        engine.elapsed = -10
        XCTAssertEqual(engine.elapsed, 0, accuracy: 0.05)
    }

    func testStopClearsDuration() throws {
        let data = makeFixtureWAVData(seconds: 1.0)
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

    /// `AVFileType` has no FLAC case in this SDK (a real CI compile failure
    /// on `AVFileType.flac` caught that — see `fileTypeHint`'s doc comment),
    /// so FLAC's magic bytes deliberately fall through to `nil` here, same as
    /// any other unrecognized container, and `AVAudioPlayer` sniffs it itself.
    func testFileTypeHintReturnsNilForFLAC() {
        let bytes: [UInt8] = [0x66, 0x4C, 0x61, 0x43] + [UInt8](repeating: 0, count: 8) // "fLaC"
        XCTAssertNil(VaultAudioPlaybackEngine.fileTypeHint(for: Data(bytes)))
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
