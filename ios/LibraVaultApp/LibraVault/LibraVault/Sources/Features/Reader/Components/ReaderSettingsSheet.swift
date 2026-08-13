import SwiftUI

enum ReaderLayoutMode: String, CaseIterable {
    case paginated = "Paginated"
    case scrolling = "Scrolling"
}

/// Decides which sections of [ReaderSettingsSheet] apply to a given book format.
///
/// Pure functions rather than expressions inlined at the call site, for the same reason
/// [ReaderTapZone] is its own type: the rules are easy to get subtly wrong and there is
/// no other way to test them — a SwiftUI `View`'s body can't be asserted on without
/// snapshot infrastructure this project doesn't have.
///
/// The specific mistake this guards against already happened once: `showReadAloud` was
/// first written as `format != .markdown`, which left `.mobi`/`.cbz` (both mapped by
/// LibraryFileScanner) showing a Read Aloud button that AppState.startPlayback then
/// silently refused, i.e. a dead control. Keeping the predicate here, next to its
/// siblings and covered by tests over *every* MediaFormat case, makes that class of
/// drift visible.
enum ReaderSettingsAvailability {

    /// False for PDF — a rendered PDF page is a fixed image of the book's real layout,
    /// so text size / line spacing / font family have nothing to apply to.
    static func showFontControls(for format: MediaFormat) -> Bool {
        format != .pdf
    }

    /// False for any format with no chapter parser (mobi, cbz — Markdown joined
    /// EPUB/PDF on the "shown" side in #124). Deliberately delegates to the same
    /// predicate `AppState.startPlayback` guards on, so the control and the action it
    /// triggers can never disagree about which formats can actually be narrated.
    static func showReadAloud(for format: MediaFormat) -> Bool {
        BookContentProvider.supportsChapterParsing(format)
    }

    /// False for Markdown — MarkdownReaderContent is a single continuous scroll with no
    /// pagination, so the Paginated/Scrolling toggle has nothing to switch.
    static func showLayoutMode(for format: MediaFormat) -> Bool {
        format != .markdown
    }
}

/// Mirrors Android's ReaderSettingsSheet (feature/reader/components) — Theme, text size,
/// line spacing, font, and layout mode. Uses app-chrome colors (LibraVaultColor.*), not
/// the reading theme, matching Android's reference screenshots: the sheet renders as a
/// light Material surface even when reading in Dark or Sepia mode.
struct ReaderSettingsSheet: View {
    @Binding var theme: ReadingTheme
    @Binding var fontSize: Double
    @Binding var lineSpacing: Double
    @Binding var fontDesign: Font.Design
    @Binding var mode: ReaderLayoutMode
    let isSpeaking: Bool
    let onToggleSpeaking: () -> Void
    /// False for PDF — a rendered PDF page is a fixed image of the book's real
    /// layout, so text size/line spacing/font family have nothing to apply to.
    /// Mirrors Android's ReaderSettingsSheet(showFontControls:).
    var showFontControls: Bool = true
    /// False for any format without a chapter parser — mobi, cbz (Markdown gained a
    /// real one in #124). Callers should pass
    /// `BookContentProvider.supportsChapterParsing(book.format)` rather than testing a
    /// single format, keeping this in step with AppState.startPlayback's own guard.
    /// Offering the button regardless of that guard started a playback session over
    /// empty text and pushed an idle Player screen; with startPlayback refusing
    /// genuinely-unsupported formats, an ungated button would instead be a silent
    /// no-op. Android has no Markdown TTS path at all as of #124 — see that issue's
    /// own writeup for why the platforms diverge here.
    var showReadAloud: Bool = true
    /// False for Markdown — MarkdownReaderContent is a single continuous scroll with
    /// no pagination, so the Paginated/Scrolling toggle has nothing to switch.
    var showLayoutMode: Bool = true

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: LibraVaultSpacing.xl) {
                Text("Reading settings")
                    .font(LibraVaultTypography.headlineSmall)
                    .foregroundStyle(LibraVaultColor.onSurface)

                settingSection("Theme") {
                    chipRow(ReadingTheme.allCases, label: \.label, isSelected: { $0 == theme }) { theme = $0 }
                }

                if showFontControls {
                    settingSection("Text size", value: "\(Int(fontSize * 100))%") {
                        Slider(value: $fontSize, in: 0.8...1.5, step: 0.1)
                            .tint(LibraVaultColor.primary)
                    }

                    settingSection("Line spacing", value: String(format: "%.1f×", lineSpacing)) {
                        Slider(value: $lineSpacing, in: 1.0...2.0, step: 0.1)
                            .tint(LibraVaultColor.primary)
                    }

                    settingSection("Font") {
                        HStack(spacing: LibraVaultSpacing.sm) {
                            FilterChip(title: "System", isSelected: fontDesign == .default) { fontDesign = .default }
                            FilterChip(title: "Serif", isSelected: fontDesign == .serif) { fontDesign = .serif }
                            FilterChip(title: "Monospace", isSelected: fontDesign == .monospaced) { fontDesign = .monospaced }
                        }
                    }
                }

                if showLayoutMode {
                    settingSection("Mode") {
                        chipRow(ReaderLayoutMode.allCases, label: \.rawValue, isSelected: { $0 == mode }) { mode = $0 }
                    }
                }

                if showReadAloud {
                    Divider()

                    // TODO: Move to the dedicated Player screen once it exists (Phase 4 of
                    // the UI parity plan) — kept here for now so restyling the Reader
                    // toolbar doesn't regress the one working piece of TTS functionality.
                    Button(action: onToggleSpeaking) {
                        Label(
                            isSpeaking ? "Stop Reading Aloud" : "Read Aloud",
                            systemImage: isSpeaking ? "speaker.slash.fill" : "speaker.wave.2.fill"
                        )
                        .font(LibraVaultTypography.bodyLarge)
                        .foregroundStyle(LibraVaultColor.primary)
                    }
                }
            }
            .padding(LibraVaultSpacing.lg)
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .background(LibraVaultColor.surface)
    }

    @ViewBuilder
    private func settingSection<Content: View>(_ title: String, value: String? = nil, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: LibraVaultSpacing.sm) {
            HStack {
                Text(title)
                    .font(LibraVaultTypography.titleMedium)
                    .foregroundStyle(LibraVaultColor.onSurface)
                if let value {
                    Spacer()
                    Text(value)
                        .font(LibraVaultTypography.bodyMedium)
                        .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                }
            }
            content()
        }
    }

    private func chipRow<T: Hashable>(_ items: [T], label: @escaping (T) -> String, isSelected: @escaping (T) -> Bool, onSelect: @escaping (T) -> Void) -> some View {
        HStack(spacing: LibraVaultSpacing.sm) {
            ForEach(items, id: \.self) { item in
                FilterChip(title: label(item), isSelected: isSelected(item)) {
                    onSelect(item)
                }
            }
        }
    }
}

#Preview {
    ReaderSettingsSheet(
        theme: .constant(.dark),
        fontSize: .constant(1.0),
        lineSpacing: .constant(1.4),
        fontDesign: .constant(.default),
        mode: .constant(.paginated),
        isSpeaking: false,
        onToggleSpeaking: {}
    )
}
