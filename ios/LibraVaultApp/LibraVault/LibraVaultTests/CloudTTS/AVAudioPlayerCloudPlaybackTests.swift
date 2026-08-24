import XCTest
@testable import LibraVault

/// Mirrors `VaultAudioPlaybackEngineTests`' own scope and reasoning — deliberately
/// exercises only `load(data:)` (pure decode, no audio hardware), never
/// `play(data:)`/`pause()`/`resume()`/`stop()`, which are guarded by
/// `isRunningUnderXCTest` and unsafe to run unattended in the CI Simulator (see
/// `AVAudioPlayerCloudPlayback`'s own doc comment).
///
/// There's no equivalent "successfully decodes real audio" test here the way
/// `VaultAudioPlaybackEngineTests` has one for a hand-written WAV fixture: this class
/// always passes a FIXED `fileTypeHint: .mp3` (every vendor is asked for MP3 — see
/// `CloudPlayback`'s doc comment), and hand-writing a valid, minimal MP3 frame byte-for-
/// byte (unlike a classic-PCM WAV header) wasn't done here — flagging this as a real,
/// intentional coverage gap rather than silently claiming full parity with that file.
final class AVAudioPlayerCloudPlaybackTests: XCTestCase {

    func testLoadThrowsForDataThatIsNotAnAudioFile() {
        let notAudio = Data("this is not an audio file".utf8)
        let playback = AVAudioPlayerCloudPlayback()

        XCTAssertThrowsError(try playback.load(data: notAudio))
    }

    func testLoadThrowsForEmptyData() {
        let playback = AVAudioPlayerCloudPlayback()

        XCTAssertThrowsError(try playback.load(data: Data()))
    }
}
