import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @EnvironmentObject var appState: AppState
    @State private var isPickingVaultFolder = false
    @State private var loggingEnabled: Bool
    /// Drives the remove-vault confirmation alert below — set by the per-row trash
    /// button, matching Android's `vaultToRemove` (SettingsScreen.kt) rather than
    /// deleting on tap/swipe with no confirmation.
    @State private var vaultPendingRemoval: Vault?
    private let logStore = LibraVaultLogStore()

    private let skipDurationPresets: [Double] = [10, 15, 30, 45, 60]

    init() {
        let store = LibraVaultLogStore()
        _loggingEnabled = State(initialValue: store.isEnabled)
    }

    var body: some View {
        Form {
            vaultsSection
            readingSection
            playbackSection
            ttsSection
            // No "Appearance" section: Android's only control there is Material You
            // dynamic color, which has no iOS equivalent — nothing honest to put here.
            privacySection
            aboutSection
            helpSection
            supportSection
        }
        .navigationTitle("Settings")
    }

    // MARK: - Vaults

    private var vaultsSection: some View {
        Section {
            ForEach(appState.vaults) { vault in
                HStack {
                    Image(systemName: "folder.fill")
                        .foregroundStyle(LibraVaultColor.primary)
                    Text(vault.displayName)
                        .foregroundStyle(LibraVaultColor.onSurface)
                    Spacer()
                    // Trash icon + confirm alert (below), not swipe/long-press —
                    // matches Android's VaultRow (SettingsScreen.kt) for parity and
                    // discoverability. Deliberately not `.onDelete`: a bare swipe
                    // would delete the vault with no confirmation at all.
                    Button {
                        vaultPendingRemoval = vault
                    } label: {
                        Image(systemName: "trash")
                            .foregroundStyle(.red)
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Remove vault")
                }
            }

            Button(action: { isPickingVaultFolder = true }) {
                Label("Add Vault", systemImage: "plus.circle")
            }
            .foregroundStyle(LibraVaultColor.primary)
        } header: {
            sectionHeader("Vaults")
        }
        .fileImporter(isPresented: $isPickingVaultFolder, allowedContentTypes: [.folder]) { result in
            if case .success(let pickedURL) = result {
                appState.addVault(pickedURL: pickedURL)
            }
        }
        // Copy mirrors Android's remove-vault AlertDialog (SettingsScreen.kt) word
        // for word, down to the quoted display name.
        .alert(
            "Remove vault?",
            isPresented: Binding(
                get: { vaultPendingRemoval != nil },
                set: { isPresented in if !isPresented { vaultPendingRemoval = nil } }
            ),
            presenting: vaultPendingRemoval
        ) { vault in
            Button("Remove", role: .destructive) {
                vaultPendingRemoval = nil
                Task { await appState.removeVault(vault) }
            }
            Button("Cancel", role: .cancel) {
                vaultPendingRemoval = nil
            }
        } message: { vault in
            Text("This will remove \"\(vault.displayName)\" and all its items from the library.")
        }
    }

    // MARK: - Reading

    private var readingSection: some View {
        Section {
            VStack(alignment: .leading, spacing: LibraVaultSpacing.sm) {
                Text("Default theme")
                    .foregroundStyle(LibraVaultColor.onSurface)
                Text("Applied when opening a book or PDF")
                    .font(LibraVaultTypography.bodySmall)
                    .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                HStack(spacing: LibraVaultSpacing.sm) {
                    ForEach(ReadingTheme.allCases, id: \.self) { theme in
                        FilterChip(title: theme.label, isSelected: appState.defaultReadingTheme == theme) {
                            appState.defaultReadingTheme = theme
                        }
                    }
                }
            }
            .padding(.vertical, LibraVaultSpacing.xs)
        } header: {
            sectionHeader("Reading")
        }
    }

    // MARK: - Playback

    private var playbackSection: some View {
        Section {
            VStack(alignment: .leading, spacing: LibraVaultSpacing.sm) {
                HStack {
                    Text("Default speed")
                        .foregroundStyle(LibraVaultColor.onSurface)
                    Spacer()
                    Text(formatPlaybackSpeed(appState.defaultPlaybackSpeed))
                        .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                }
                Slider(value: $appState.defaultPlaybackSpeed, in: 0.5...3.0, step: 0.25)
                    .tint(LibraVaultColor.primary)
            }
            .padding(.vertical, LibraVaultSpacing.xs)

            VStack(alignment: .leading, spacing: LibraVaultSpacing.sm) {
                Text("Skip duration")
                    .foregroundStyle(LibraVaultColor.onSurface)
                Text("\(Int(appState.skipDurationSeconds)) seconds per skip")
                    .font(LibraVaultTypography.bodySmall)
                    .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                HStack(spacing: LibraVaultSpacing.sm) {
                    ForEach(skipDurationPresets, id: \.self) { seconds in
                        FilterChip(title: "\(Int(seconds))s", isSelected: appState.skipDurationSeconds == seconds) {
                            appState.skipDurationSeconds = seconds
                        }
                    }
                }
            }
            .padding(.vertical, LibraVaultSpacing.xs)

            Toggle("Auto-hide mini-player", isOn: $appState.miniPlayerAutoHideEnabled)
                .tint(LibraVaultColor.primary)
        } header: {
            sectionHeader("Playback")
        } footer: {
            Text("Tucks the mini-player away after a few seconds idle, leaving a small hint at the bottom to bring it back.")
                .font(LibraVaultTypography.bodySmall)
                .foregroundStyle(LibraVaultColor.onSurfaceVariant)
        }
    }

    // MARK: - Text-to-Speech

    /// Mirrors Android's TtsSettingsSection (feature/settings/.../ui/TtsSettingsSection.kt).
    /// Pocket TTS's voice model ships bundled with the app on iOS (unlike Android's
    /// on-first-use download - see PocketModelManager.swift), so there's no download
    /// progress UI to show here; picking it just switches the active engine.
    private var ttsSection: some View {
        Section {
            Picker("Voice", selection: $appState.ttsEngineType) {
                ForEach(TTSEngineType.allCases, id: \.self) { type in
                    Text(type.displayName).tag(type)
                }
            }
            .pickerStyle(.segmented)
        } header: {
            sectionHeader("Text-to-Speech")
        } footer: {
            Text("On-device voice runs fully offline, with no network access.")
                .font(LibraVaultTypography.bodySmall)
                .foregroundStyle(LibraVaultColor.onSurfaceVariant)
        }
    }

    // MARK: - Privacy & Diagnostics

    private var privacySection: some View {
        Section {
            Toggle("Local crash logging", isOn: $loggingEnabled)
                .tint(LibraVaultColor.primary)
                .onChange(of: loggingEnabled) { _, newValue in
                    logStore.isEnabled = newValue
                }
            if loggingEnabled {
                NavigationLink(destination: LogViewerView()) {
                    Text("View Logs")
                }
            }
        } header: {
            sectionHeader("Privacy & Diagnostics")
        } footer: {
            Text("Logs are stored only on this device and never transmitted.")
        }
    }

    // MARK: - About

    private var aboutSection: some View {
        Section {
            HStack {
                Text("Version")
                    .foregroundStyle(LibraVaultColor.onSurface)
                Spacer()
                Text("3.0.0-alpha")
                    .foregroundStyle(LibraVaultColor.onSurfaceVariant)
            }

            NavigationLink(destination: AboutView()) {
                Text("About LibraVault")
            }
        } header: {
            sectionHeader("About")
        }
    }

    // MARK: - Help

    /// Field feedback (issue #151): "Ik mis een help menu" — there was no in-app
    /// Help/FAQ anywhere. This is a plain static screen, not a support ticket
    /// system — LibraVault has no networking, so there's nothing honest to wire a
    /// "contact us" form to (same reasoning as supportSection's missing donate
    /// button).
    private var helpSection: some View {
        Section {
            NavigationLink(destination: HelpView()) {
                Text("Help & FAQ")
            }
        } header: {
            sectionHeader("Help")
        }
    }

    // MARK: - Support Development

    /// Identical on every flavor and platform (Android Play, Android F-Droid,
    /// iOS) — see feature:settings's `SUPPORT_URL` on the Android side (kept
    /// in sync by hand, since Kotlin and Swift can't share a constant here).
    /// Apple rejects apps that show crypto addresses/QR codes inside the app's
    /// own UI (unapproved tipping / IAP bypass); the consistent answer, not
    /// just the Apple-compliant one, is that no platform renders an address
    /// in-app — this hands off to the website instead, which is free to show
    /// BTC/XMR addresses since it isn't inside the app binary.
    static let supportURL = URL(string: "https://libravault.xyz/support.html")!

    private var supportSection: some View {
        Section {
            if appState.isSupporter {
                Text("★ You're a Supporter — thank you!")
                    .font(LibraVaultTypography.bodyMedium.weight(.semibold))
                    .foregroundStyle(LibraVaultColor.secondary)
            }
            Text("LibraVault is free — no ads, no tracking, no accounts. If this app brings you joy, consider supporting its development. BTC and XMR donation addresses are on the website, not in this app.")
                .font(LibraVaultTypography.bodySmall)
                .foregroundStyle(LibraVaultColor.onSurfaceVariant)
            Link(destination: Self.supportURL) {
                Text("Support the Project")
            }
        } header: {
            sectionHeader("Support Development")
        }
    }

    private func sectionHeader(_ title: String) -> some View {
        Text(title)
            .font(LibraVaultTypography.titleSmall)
            .foregroundStyle(LibraVaultColor.primary)
    }
}

struct LogViewerView: View {
    private let logStore = LibraVaultLogStore()
    @State private var logs: String = ""

    var body: some View {
        VStack {
            HStack {
                Text("Logs")
                    .font(.caption)
                    .foregroundColor(.secondary)

                Spacer()

                Button(action: { refreshLogs() }) {
                    Image(systemName: "arrow.clockwise")
                        .font(.caption)
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 8)

            ScrollView {
                Text(logs)
                    .font(.system(.caption, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
                    .textSelection(.enabled)
            }
            .background(Color(.systemGray6))
            .cornerRadius(8)
            .padding()

            HStack(spacing: 12) {
                Button(action: {
                    logStore.clearLogs()
                    refreshLogs()
                }) {
                    Label("Clear", systemImage: "trash")
                        .font(.caption)
                }
                .buttonStyle(.bordered)

                Spacer()

                Button(action: { shareLogs() }) {
                    Label("Share", systemImage: "square.and.arrow.up")
                        .font(.caption)
                }
                .buttonStyle(.bordered)
            }
            .padding()
        }
        .navigationTitle("Logs")
        .onAppear { refreshLogs() }
    }

    private func refreshLogs() {
        logs = logStore.readLogs()
    }

    private func shareLogs() {
        let pasteboard = UIPasteboard.general
        pasteboard.string = logs
    }
}

struct AboutView: View {
    // Field feedback (#151): "Tekst past niet" — the About paragraph was showing up
    // truncated ("...privacy-first e-book reade...") on a real device. This VStack
    // used to lay out directly with no ScrollView around it: on a screen short
    // enough (or with Dynamic Type large enough) that the icon + both title texts +
    // the About paragraph + the Privacy bullets + the Spacer + the link don't all
    // fit in one screen's height, SwiftUI compresses the flexible Text views down to
    // fit rather than letting the VStack grow past the screen — visually
    // indistinguishable from truncation, but the text is still one line internally,
    // which is why it also stopped mid-sentence rather than wrapping to a second
    // line. Wrapping in a ScrollView lets the VStack take its full intrinsic height
    // and scroll instead, the same fix already applied to HelpView below.
    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                VStack(spacing: 8) {
                    Image(systemName: "books.vertical")
                        .font(.system(size: 64))
                        .foregroundStyle(LibraVaultColor.primary)

                    Text("LibraVault")
                        .font(LibraVaultTypography.headlineMedium)
                        .foregroundStyle(LibraVaultColor.onBackground)

                    Text("Your Personal E-Book Library")
                        .font(LibraVaultTypography.bodySmall)
                        .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                }
                .padding()

                VStack(alignment: .leading, spacing: 12) {
                    Text("About")
                        .font(LibraVaultTypography.titleMedium)
                        .foregroundStyle(LibraVaultColor.onBackground)

                    Text("LibraVault is a privacy-first e-book reader and library manager for iOS, focused on giving you full control over your reading experience without tracking or data collection.")
                        .font(LibraVaultTypography.bodyMedium)
                        .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding()

                VStack(alignment: .leading, spacing: 12) {
                    Text("Privacy")
                        .font(LibraVaultTypography.titleMedium)
                        .foregroundStyle(LibraVaultColor.onBackground)

                    BulletPoint(text: "No cloud sync or accounts")
                    BulletPoint(text: "All data stored locally")
                    BulletPoint(text: "No tracking or analytics")
                    BulletPoint(text: "Open source")
                }
                .padding()

                Link("GitHub Repository", destination: URL(string: "https://github.com/LibraVault/reader")!)
                    .font(LibraVaultTypography.bodySmall)
                    .foregroundStyle(LibraVaultColor.primary)
                    .padding()
            }
        }
        .navigationTitle("About LibraVault")
    }
}

/// One question in HelpView's FAQ list.
struct HelpTopic: Identifiable {
    let id = UUID()
    let question: String
    let answer: String
}

struct HelpView: View {
    private let topics: [HelpTopic] = [
        HelpTopic(
            question: "How do I add books?",
            answer: "In Settings → Vaults, tap \"Add Vault\" and pick a folder. LibraVault reads every EPUB, PDF, and Markdown file inside it — nothing is copied off your device."
        ),
        HelpTopic(
            question: "How do I turn pages or scroll?",
            answer: "Tap the left or right edge of the page to go back or forward, or tap the center to show/hide the toolbar. Switch between paginated and continuous-scroll layout from the reader's ⋯ menu → Reading Settings."
        ),
        HelpTopic(
            question: "How do I add or view a bookmark?",
            answer: "The bookmark icon in the reader's top-right toolbar opens your saved bookmarks — tap it to view, edit, or delete them. Press and hold that same icon to add a new bookmark at your current position."
        ),
        HelpTopic(
            question: "Where is Read Aloud?",
            answer: "Tap the Play icon in the reader's top-right toolbar (next to the bookmark icon) to start Read Aloud from your current position for EPUB, PDF, or Markdown. Voice and playback speed can be changed from Settings → Text-to-Speech and Playback."
        ),
        HelpTopic(
            question: "How do I change the reading theme or font?",
            answer: "Use the reader's ⋯ menu to cycle the theme (Dark/Light/Sepia), or open Reading Settings from that same menu for font size, spacing, and font."
        ),
        HelpTopic(
            question: "Does LibraVault need an internet connection?",
            answer: "No. LibraVault works fully offline — no accounts, no cloud sync, no tracking. Everything, including on-device Read Aloud, runs locally."
        ),
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: LibraVaultSpacing.xl) {
                ForEach(topics) { topic in
                    VStack(alignment: .leading, spacing: LibraVaultSpacing.sm) {
                        Text(topic.question)
                            .font(LibraVaultTypography.titleMedium)
                            .foregroundStyle(LibraVaultColor.onBackground)
                        Text(topic.answer)
                            .font(LibraVaultTypography.bodyMedium)
                            .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .padding(LibraVaultSpacing.lg)
        }
        .navigationTitle("Help")
    }
}

struct BulletPoint: View {
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text("•")
            Text(text)
                .foregroundStyle(LibraVaultColor.onSurfaceVariant)
        }
    }
}

#Preview {
    NavigationStack {
        SettingsView()
    }
    .environmentObject(AppState())
}
