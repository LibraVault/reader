/// Decides whether the reader needs an escape hatch independent of the immersive-mode
/// center-tap toggle (`showToolbar` in ReaderView) — see #293. `MarkdownReaderContent`
/// layers `.textSelection(.enabled)` directly over its center tap-zone, which can
/// swallow a single tap on iOS (selectable text installs its own tap-to-place-cursor
/// gesture) and leave `showToolbar` stuck `false` with the whole navigation bar —
/// including the back button — hidden and no way back to the Library. EPUB/PDF don't
/// layer text selection over their tap zone, so they aren't at risk and don't need this
/// fallback.
///
/// A pure function rather than an expression inlined in ReaderView's body, for the same
/// reason `ReaderTapZone` and `ReaderSettingsAvailability` are their own types: a
/// SwiftUI `View`'s body can't be asserted on directly without snapshot infrastructure
/// this project doesn't have.
enum ReaderExitAffordance {
    static func isNeeded(format: MediaFormat, showToolbar: Bool) -> Bool {
        format == .markdown && !showToolbar
    }
}
