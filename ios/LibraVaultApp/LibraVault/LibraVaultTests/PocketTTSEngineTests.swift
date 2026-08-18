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
/// Also covers `ttsCallback`'s retain/release contract (see the "ttsCallback"
/// section below) against a real, attached-but-not-started `AVAudioPlayerNode`
/// - attach/connect are pure graph-configuration calls that touch no audio
/// hardware or session, unlike `AVAudioEngine.start()`/
/// `AVAudioSession.setActive(true)`, which are what actually hung CI before
/// (see the class's own doc comment and `DomainBridge.swift`'s identical
/// story - both name session activation specifically, never node
/// attach/connect). Apple's own AVAudioEngine pattern schedules buffers on an
/// attached-but-unstarted node before ever calling `engine.start()`, so this
/// mirrors supported API usage, not a guess.
///
/// Deliberately NOT covered here: real audio output (`speak` actually
/// producing sound through a *running* engine/session) and a full
/// `generateWithConfig` synthesis round trip. Both require the exact
/// AVAudioSession/AVAudioEngine activation path that hung CI before -
/// manual/TestFlight verification only, tracked as a known gap rather than
/// guessed at here.
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

    // MARK: - ttsCallback retain/release contract

    /// Engine kept alive for the duration of a single test - AVAudioEngine
    /// tears down its graph if deallocated, and `PlaybackContext.node` holds
    /// its `AVAudioPlayerNode` only weakly-in-spirit (the engine, not the
    /// context, owns the attach relationship).
    private var playbackEngine: AVAudioEngine!

    /// Attaches and connects a real `AVAudioPlayerNode`, matching exactly
    /// what `initialize()` does before `audioEngine.start()` - but never
    /// starts the engine or touches `AVAudioSession`, which is the
    /// documented hang boundary. `scheduleBuffer` is explicitly supported by
    /// Apple on an attached-but-unstarted node (that's the standard
    /// prepare-then-start sequence), so this exercises `ttsCallback`'s real
    /// `context.node.scheduleBuffer(...)` call without approaching the
    /// hardware/session activation that caused the CI timeouts.
    private func makeAttachedPlayerNode(format: AVAudioFormat) -> AVAudioPlayerNode {
        let engine = AVAudioEngine()
        let node = AVAudioPlayerNode()
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: format)
        playbackEngine = engine
        return node
    }

    func testTtsCallbackSchedulesBufferAndReturnsContinueForValidSamples() {
        let format = makeFormat()
        let node = makeAttachedPlayerNode(format: format)
        let context = PlaybackContext(node: node, format: format)
        let unmanaged = Unmanaged.passRetained(context)
        defer { unmanaged.release() }

        let samples: [Float] = [0.1, -0.2, 0.3]
        let result = samples.withUnsafeBufferPointer { ptr in
            PocketTTSEngine.ttsCallback(ptr.baseAddress, Int32(samples.count), 0, unmanaged.toOpaque())
        }

        // 1 == "continue generating", the only value sherpa-onnx accepts to
        // keep synthesizing further chunks of the same utterance.
        XCTAssertEqual(result, 1)
    }

    /// Simulates the real usage shape in `speak()`: one `passRetained()` up
    /// front, the callback invoked repeatedly (once per generated chunk,
    /// using `takeUnretainedValue()` - no retain of its own), then exactly
    /// one `release()` after generation finishes. An unbalanced retain here
    /// would leak `PlaybackContext` every utterance; an extra release would
    /// crash. Running several simulated chunks through one retain/release
    /// pair without crashing is the regression check for that contract.
    func testTtsCallbackAcrossMultipleChunksBalancesAgainstASingleRetainAndRelease() {
        let format = makeFormat()
        let node = makeAttachedPlayerNode(format: format)
        let context = PlaybackContext(node: node, format: format)
        let unmanaged = Unmanaged.passRetained(context)
        let arg = unmanaged.toOpaque()

        for chunkIndex in 0..<5 {
            let samples: [Float] = [Float(chunkIndex) * 0.1, Float(chunkIndex) * -0.1]
            let result = samples.withUnsafeBufferPointer { ptr in
                PocketTTSEngine.ttsCallback(ptr.baseAddress, Int32(samples.count), 0, arg)
            }
            XCTAssertEqual(result, 1)
        }

        unmanaged.release()
    }

    func testTtsCallbackWithNilSamplesReturnsContinueWithoutDereferencing() {
        let format = makeFormat()
        let node = makeAttachedPlayerNode(format: format)
        let context = PlaybackContext(node: node, format: format)
        let unmanaged = Unmanaged.passRetained(context)
        defer { unmanaged.release() }

        let result = PocketTTSEngine.ttsCallback(nil, 4, 0, unmanaged.toOpaque())

        XCTAssertEqual(result, 1)
    }

    func testTtsCallbackWithZeroSampleCountReturnsContinueWithoutDereferencing() {
        let format = makeFormat()
        let node = makeAttachedPlayerNode(format: format)
        let context = PlaybackContext(node: node, format: format)
        let unmanaged = Unmanaged.passRetained(context)
        defer { unmanaged.release() }

        let samples: [Float] = [0.1, -0.2, 0.3]
        let result = samples.withUnsafeBufferPointer { ptr in
            PocketTTSEngine.ttsCallback(ptr.baseAddress, 0, 0, unmanaged.toOpaque())
        }

        XCTAssertEqual(result, 1)
    }

    func testTtsCallbackWithNilArgReturnsContinueWithoutDereferencing() {
        let samples: [Float] = [0.1, -0.2, 0.3]
        let result = samples.withUnsafeBufferPointer { ptr in
            PocketTTSEngine.ttsCallback(ptr.baseAddress, Int32(samples.count), 0, nil)
        }

        XCTAssertEqual(result, 1)
    }
}
