import Foundation
@testable import LibraVault

/// A test double for NowPlayingManaging with no MediaPlayer calls at all — mirrors
/// FakeAudioPlaybackEngine's role for AudioPlaybackEngineProtocol: real
/// MPNowPlayingInfoCenter/MPRemoteCommandCenter aren't safe to assert against
/// directly (no public way to read back what was set, and remote-command
/// registration is process-global state), so AppState's Now Playing integration
/// (issue #309) can only be exercised in tests through this fake, injected via
/// AppState's `nowPlayingManager:` init parameter.
final class FakeNowPlayingManager: NowPlayingManaging {
    var onPlay: (() -> Void)?
    var onPause: (() -> Void)?
    var onSkipForward: (() -> Void)?
    var onSkipBackward: (() -> Void)?

    private(set) var updateCalls: [NowPlayingSnapshot] = []
    private(set) var clearCallCount = 0

    /// The most recent snapshot passed to `update(_:)`, or nil if it's never been
    /// called — the shape most tests want. Independent of `clearCallCount`: `clear()`
    /// doesn't reset this, so a test asserting "ended up cleared" should check
    /// `clearCallCount` instead of relying on `lastUpdate` going nil.
    var lastUpdate: NowPlayingSnapshot? { updateCalls.last }

    func update(_ snapshot: NowPlayingSnapshot) {
        updateCalls.append(snapshot)
    }

    func clear() {
        clearCallCount += 1
    }
}
