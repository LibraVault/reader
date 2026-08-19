import Foundation
import MediaPlayer
import UIKit

/// Snapshot of what Control Center/the lock screen should currently show for the
/// active playback session (issue #309) — AppState's playback state translated into
/// exactly what `NowPlayingManaging` needs, so call sites never touch MediaPlayer's
/// raw dictionary-key API (`MPNowPlayingInfoPropertyElapsedPlaybackTime` etc.)
/// directly.
struct NowPlayingSnapshot {
    let title: String
    let artist: String
    /// The current chapter's title, shown as the album line — nil for audiobooks and
    /// single-chapter text books (see AppState.nowPlayingChapterCount's doc comment;
    /// audiobooks are always reported as 1 chapter), where there's nothing more
    /// specific to show than the title/artist already convey.
    let chapterTitle: String?
    let elapsedSeconds: Double
    let totalSeconds: Double
    let playbackRate: Double
    let isPlaying: Bool
    /// Mirrors PlayerView's skip-back/skip-forward buttons (AppState.skipDurationSeconds)
    /// so the lock-screen/Control Center skip buttons jump the same distance.
    let skipIntervalSeconds: Double
    /// Real cover art already extracted/cached by CoverArtExtractor/CoverArtCache — nil
    /// falls back to whatever generic artwork the system shows on its own. The
    /// generated format-badge placeholder (CoverArtView's gradient fallback) is a
    /// separate, cross-platform issue and deliberately not reused here.
    let artwork: UIImage?
}

/// The surface of system Now Playing / remote-command integration AppState depends
/// on (issue #309) — lets tests inject a fake that never touches
/// `MPNowPlayingInfoCenter`/`MPRemoteCommandCenter`, the same seam
/// `AudioPlaybackEngineProtocol` already uses for AVFoundation: both are real system
/// singletons that are awkward (remote-command registration is process-global state)
/// or impossible (`MPNowPlayingInfoCenter` has no public way to read back what was
/// set) to assert against directly from a unit test.
protocol NowPlayingManaging: AnyObject {
    /// Fired by the system play/pause commands (Control Center, lock screen, a
    /// headphone remote's single button via `togglePlayPauseCommand`). AppState
    /// decides whether "play" or "pause" is actually appropriate for the current
    /// state — see its `remotePlay`/`remotePause` — so these fire unconditionally
    /// whenever the system reports the corresponding command.
    var onPlay: (() -> Void)? { get set }
    var onPause: (() -> Void)? { get set }
    /// Fired by the system skip-forward/skip-backward commands (lock-screen ±buttons,
    /// some headphone remotes). Takes no argument — AppState reads its own live
    /// `skipDurationSeconds` at call time, matching the in-app skip buttons exactly
    /// even if the preference changed since these were registered.
    var onSkipForward: (() -> Void)? { get set }
    var onSkipBackward: (() -> Void)? { get set }

    /// Called whenever AppState's playback state changes in a way Control Center
    /// should reflect: start, pause, resume, seek, chapter change, speed change.
    func update(_ snapshot: NowPlayingSnapshot)

    /// Called from stopPlayback — removes LibraVault from Control Center/the lock
    /// screen entirely, rather than leaving stale metadata visible for a book that's
    /// no longer playing.
    func clear()
}

/// Real implementation, wrapping `MPNowPlayingInfoCenter.default()` and
/// `MPRemoteCommandCenter.shared()` — both process-wide singletons, so only one of
/// these should ever be live in the app at a time (AppState owns exactly one, via its
/// `nowPlayingManager:` init parameter).
final class SystemNowPlayingManager: NowPlayingManaging {
    var onPlay: (() -> Void)?
    var onPause: (() -> Void)?
    var onSkipForward: (() -> Void)?
    var onSkipBackward: (() -> Void)?

    private let infoCenter = MPNowPlayingInfoCenter.default()
    private let commandCenter = MPRemoteCommandCenter.shared()
    private var lastKnownIsPlaying = false

    /// `xcodebuild test`'s CI Simulator has no real audio hardware, and this repo has
    /// already confirmed (AVAudioSession activation, AVSpeechSynthesizer — see
    /// TTSEngineBridge/AudioPlaybackEngine) that talking to the Simulator's audio/media
    /// daemons from that environment can hang a run indefinitely. MPNowPlayingInfoCenter
    /// and MPRemoteCommandCenter talk to a similar system daemon (nowplayingd) to
    /// publish Control Center/lock-screen state, and there's no local Xcode/Simulator
    /// available to verify it's safe there — so this guards the same way, out of an
    /// abundance of caution, until a real device/Simulator run confirms otherwise (see
    /// issue #309's own "not verified end-to-end" caveat).
    private static var isRunningUnderXCTest: Bool {
        ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
    }

    init() {
        guard !Self.isRunningUnderXCTest else { return }
        registerCommands()
    }

    private func registerCommands() {
        commandCenter.playCommand.addTarget { [weak self] _ in
            guard let self else { return .commandFailed }
            self.onPlay?()
            return .success
        }
        commandCenter.pauseCommand.addTarget { [weak self] _ in
            guard let self else { return .commandFailed }
            self.onPause?()
            return .success
        }
        commandCenter.togglePlayPauseCommand.addTarget { [weak self] _ in
            guard let self else { return .commandFailed }
            if self.lastKnownIsPlaying {
                self.onPause?()
            } else {
                self.onPlay?()
            }
            return .success
        }
        commandCenter.skipForwardCommand.addTarget { [weak self] _ in
            guard let self else { return .commandFailed }
            self.onSkipForward?()
            return .success
        }
        commandCenter.skipBackwardCommand.addTarget { [weak self] _ in
            guard let self else { return .commandFailed }
            self.onSkipBackward?()
            return .success
        }
    }

    func update(_ snapshot: NowPlayingSnapshot) {
        guard !Self.isRunningUnderXCTest else { return }
        lastKnownIsPlaying = snapshot.isPlaying

        let interval = NSNumber(value: snapshot.skipIntervalSeconds)
        commandCenter.skipForwardCommand.preferredIntervals = [interval]
        commandCenter.skipBackwardCommand.preferredIntervals = [interval]

        var info: [String: Any] = [
            MPMediaItemPropertyTitle: snapshot.title,
            MPMediaItemPropertyArtist: snapshot.artist,
            MPMediaItemPropertyPlaybackDuration: snapshot.totalSeconds,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: snapshot.elapsedSeconds,
            // 0 rather than the real speed while paused — a nonzero rate tells the
            // system to keep interpolating the elapsed-time display forward on its
            // own between our updates, which it should only do while actually playing.
            MPNowPlayingInfoPropertyPlaybackRate: snapshot.isPlaying ? snapshot.playbackRate : 0,
        ]
        if let chapterTitle = snapshot.chapterTitle {
            info[MPMediaItemPropertyAlbumTitle] = chapterTitle
        }
        if let artwork = snapshot.artwork {
            info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: artwork.size) { _ in artwork }
        }
        infoCenter.nowPlayingInfo = info
        infoCenter.playbackState = snapshot.isPlaying ? .playing : .paused
    }

    func clear() {
        guard !Self.isRunningUnderXCTest else { return }
        lastKnownIsPlaying = false
        infoCenter.nowPlayingInfo = nil
        infoCenter.playbackState = .stopped
    }
}
