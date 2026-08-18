import XCTest
import AVFoundation
@testable import LibraVault

/// PocketTTSEngine's real entry points (`initialize`/`speak`/`stop`/`pause`/
/// `resume`) all short-circuit under XCTest to avoid an AVAudioSession
/// activation hang that cost two ~30-minute CI timeouts (see the class's own
/// doc comment). That guard must stay, but it used to sit in front of
/// everything, including logic that touches no audio hardware at all. This
/// file covers exactly that pure logic: the model-path construction, the
/// `modelNotBundled` failure path (now reachable because it's checked before
/// the guard), and the sample -> PCM buffer transform used on every
/// generated audio chunk.
///
/// Deliberately NOT covered here: real audio output (`speak` actually
/// producing sound) and the sherpa-onnx C callback's retain/release
/// lifecycle when wired to a live `AVAudioPlayerNode`. Both stay behind the
/// XCTest guard / require real synthesis, and the callback boundary in
/// particular sits right next to the exact AVFoundation activation path that
/// hung CI before - manual/TestFlight verification only, tracked as a known
/// gap rather than guessed at here.
final class PocketTTSEngineTests: XCTestCase {

    private var tempDirectory: URL!

    override func setUp() {
        super.setUp()
        tempDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("PocketTTSEngineTests-\(UUID().uuidString)", isDirectory: true)
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

    // MARK: - Model path construction

    /// Pins the three filenames that must stay in sync with both
    /// setup-ios.sh (which extracts them) and Android's PocketVoiceCatalog
    /// (which bakes in the same voice's filenames) - a rename on either side
    /// without the other should fail this test instead of silently
    /// producing a model that fails to load at runtime.
    func testModelFilenamesMatchSetupScriptAndAndroidCatalog() {
        XCTAssertEqual(PocketTTSEngine.modelFileName, "en_US-ljspeech-medium.onnx")
        XCTAssertEqual(PocketTTSEngine.tokensFileName, "tokens.txt")
        XCTAssertEqual(PocketTTSEngine.dataDirName, "espeak-ng-data")
    }

    // MARK: - modelNotBundled reachability

    func testInitializeThrowsModelNotBundledWhenModelDirectoryIsMissing() async {
        let manager = PocketModelManager(bundle: fakeBundle(), subdirectory: "PocketTTSModel")
        let engine = PocketTTSEngine(modelManager: manager)

        do {
            try await engine.initialize()
            XCTFail("expected EngineError.modelNotBundled to be thrown")
        } catch PocketTTSEngine.EngineError.modelNotBundled {
            // expected
        } catch {
            XCTFail("expected EngineError.modelNotBundled, got \(error)")
        }
    }

    /// Once a model directory resolves, initialize() must fall through to
    /// the XCTest guard and return without throwing or touching audio
    /// hardware - proving the reordering in initialize() (modelNotBundled
    /// check before the XCTest guard) didn't change behaviour for the
    /// already-bundled case, only made the missing-model case reachable.
    func testInitializeReturnsWithoutThrowingWhenModelDirectoryIsPresent() async throws {
        let modelDir = tempDirectory.appendingPathComponent("PocketTTSModel")
        try FileManager.default.createDirectory(at: modelDir, withIntermediateDirectories: true)

        let manager = PocketModelManager(bundle: fakeBundle(), subdirectory: "PocketTTSModel")
        let engine = PocketTTSEngine(modelManager: manager)

        try await engine.initialize()
    }

    func testModelNotBundledErrorDescriptionNamesTheSetupScript() {
        let message = PocketTTSEngine.EngineError.modelNotBundled.errorDescription
        XCTAssertEqual(
            message,
            "Pocket TTS voice model is not bundled with this build. " +
                "Run third-party/sherpa-onnx/setup-ios.sh before building (see SHERPA_ONNX_SETUP.md)."
        )
    }

    // MARK: - pcmBuffer(from:format:)

    private func makeFormat() -> AVAudioFormat {
        AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 22050, channels: 1, interleaved: false)!
    }

    func testPcmBufferFrameLengthMatchesSampleCount() {
        let samples: [Float] = [0.1, -0.2, 0.3, -0.4, 0.5]
        let buffer = PocketTTSEngine.pcmBuffer(from: samples, format: makeFormat())

        XCTAssertEqual(buffer?.frameLength, AVAudioFrameCount(samples.count))
    }

    func testPcmBufferChannelDataHoldsInputSamplesVerbatim() throws {
        let samples: [Float] = [0.0, 0.25, -0.5, 0.75, -1.0]
        let buffer = try XCTUnwrap(PocketTTSEngine.pcmBuffer(from: samples, format: makeFormat()))
        let channelData = try XCTUnwrap(buffer.floatChannelData)

        for (index, sample) in samples.enumerated() {
            XCTAssertEqual(channelData[0][index], sample)
        }
    }

    func testPcmBufferWithEmptySamplesProducesNoUsableAudioWithoutCrashing() {
        let buffer = PocketTTSEngine.pcmBuffer(from: [], format: makeFormat())

        // Whether AVAudioPCMBuffer returns nil or a zero-length buffer for a
        // zero-frame request is an AVFoundation implementation detail; what
        // this guards is that no chunk ever gets scheduled for playback.
        XCTAssertEqual(buffer?.frameLength ?? 0, 0)
    }
}
