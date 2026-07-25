import Foundation

/// PlayerView's scrub-bar labels and SleepTimerSheet's countdown used to each keep
/// their own private `m:ss` formatter with identical logic — pulled into one place
/// instead of two copies that could quietly drift apart.
func formatPlaybackTime(_ seconds: Double) -> String {
    let totalSeconds = max(0, Int(seconds))
    let minutes = totalSeconds / 60
    let secs = totalSeconds % 60
    return String(format: "%d:%02d", minutes, secs)
}
