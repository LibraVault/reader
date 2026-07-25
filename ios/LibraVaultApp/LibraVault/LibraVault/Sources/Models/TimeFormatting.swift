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

/// PlayerView's speed button and SettingsView's "Default speed" row used `%.2g`
/// (2 *significant figures*, not decimal places) — silently wrong for the sliders'
/// own 0.25 step: 1.25 rounds to "1.3", 2.75 rounds to "2.7". `%.3g` has enough
/// significant figures for every value either slider can actually produce (0.5–3.0
/// step 0.25) while still trimming trailing zeros on whole numbers ("1×", not "1.00×").
func formatPlaybackSpeed(_ speed: Double) -> String {
    "\(String(format: "%.3g", speed))×"
}
