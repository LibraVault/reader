import AVFoundation
import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @EnvironmentObject var appState: AppState
    @EnvironmentObject var encryptedVaultRuntime: EncryptedVaultRuntime
    @EnvironmentObject var billingManager: StoreKitBillingManager
    @State private var isPickingFolder = false
    @State private var loggingEnabled: Bool
    @State private var screenSecurityEnabled: Bool
    /// Drives the remove-folder confirmation alert below — set by the per-row trash
    /// button, matching Android's `vaultToRemove` (SettingsScreen.kt) rather than
    /// deleting on tap/swipe with no confirmation.
    @State private var folderPendingRemoval: Folder?
    private let logStore = LibraVaultLogStore()

    private let skipDurationPresets: [Double] = [10, 15, 30, 45, 60]

    init() {
        let store = LibraVaultLogStore()
        _loggingEnabled = State(initialValue: store.isEnabled)
        _screenSecurityEnabled = State(initialValue: VaultScreenSecurityPreference.isEnabled())
    }

    var body: some View {
        Form {
            foldersSection
            encryptedVaultsSection
            readingSection
            playbackSection
            ttsSection
            // Only ever rendered when subscribed — the real signal
            // (`StoreKitBillingManager.isSubscribed`, wired straight through per PRD
            // §8, no mock/stub — see docs/cloud-tts-premium-prd.md and issue #452).
            // The consent toggle inside stays independently off by default regardless.
            if billingManager.isSubscribed {
                CloudVoicesSection()
            }
            // No "Appearance" section: Android's only control there is Material You
            // dynamic color, which has no iOS equivalent — nothing honest to put here.
            privacySection
            aboutSection
            helpSection
            supportSection
        }
        .navigationTitle("Settings")
    }

    // MARK: - Folders

    private var foldersSection: some View {
        Section {
            ForEach(appState.folders) { folder in
                HStack {
                    Image(systemName: "folder.fill")
                        .foregroundStyle(LibraVaultColor.primary)
                    Text(folder.displayName)
                        .foregroundStyle(LibraVaultColor.onSurface)
                    Spacer()
                    // Trash icon + confirm alert (below), not swipe/long-press —
                    // matches Android's VaultRow (SettingsScreen.kt) for parity and
                    // discoverability. Deliberately not `.onDelete`: a bare swipe
                    // would delete the folder with no confirmation at all.
                    Button {
                        folderPendingRemoval = folder
                    } label: {
                        Image(systemName: "trash")
                            .foregroundStyle(.red)
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Remove folder")
                }
            }

            Button(action: { isPickingFolder = true }) {
                Label("Add Folder", systemImage: "plus.circle")
            }
            .foregroundStyle(LibraVaultColor.primary)
        } header: {
            sectionHeader("Folders")
        }
        .fileImporter(isPresented: $isPickingFolder, allowedContentTypes: [.folder]) { result in
            if case .success(let pickedURL) = result {
                appState.addFolder(pickedURL: pickedURL)
            }
        }
        // Copy mirrors Android's remove-vault AlertDialog (SettingsScreen.kt) word
        // for word, down to the quoted display name.
        .alert(
            "Remove folder?",
            isPresented: Binding(
                get: { folderPendingRemoval != nil },
                set: { isPresented in if !isPresented { folderPendingRemoval = nil } }
            ),
            presenting: folderPendingRemoval
        ) { folder in
            Button("Remove", role: .destructive) {
                folderPendingRemoval = nil
                Task { await appState.removeFolder(folder) }
            }
            Button("Cancel", role: .cancel) {
                folderPendingRemoval = nil
            }
        } message: { folder in
            Text("This will remove \"\(folder.displayName)\" and all its items from the library.")
        }
    }

    // MARK: - Encrypted Vaults

    /// Distinct from `foldersSection` above on purpose — see #323's rename:
    /// a Folder just points at files already on disk, a Vault encrypts a
    /// real, separate copy of them behind a PIN. Deliberately its own
    /// labeled section, not folded into Folders, so the two never read as
    /// the same feature.
    private var encryptedVaultsSection: some View {
        Section {
            NavigationLink {
                EncryptedVaultListView(runtime: encryptedVaultRuntime)
            } label: {
                Label("Encrypted Vaults", systemImage: "lock.fill")
            }

            // #204: mirrors Android's SwitchSetting in the same
            // "Encrypted Vaults" section (SettingsScreen.kt) — same copy,
            // same "on by default" default. Read once at Settings' own
            // appearance (via `init`, not a live `VaultScreenSecurityPreference`
            // read on every render) and written straight through on toggle,
            // matching `loggingEnabled` right above.
            Toggle("Screen Security", isOn: $screenSecurityEnabled)
                .tint(LibraVaultColor.primary)
                .onChange(of: screenSecurityEnabled) { _, newValue in
                    VaultScreenSecurityPreference.setEnabled(newValue)
                }
        } header: {
            sectionHeader("Encrypted Vaults")
        } footer: {
            Text("Vaults encrypt copies of your files behind a PIN — separate from your Folders above. Screen Security blanks vault content while screen-recorded, AirPlay-mirrored, or in the App Switcher; on by default.")
                .font(LibraVaultTypography.bodySmall)
                .foregroundStyle(LibraVaultColor.onSurfaceVariant)
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
    ///
    /// `.cloud` deliberately excluded from this picker's options (issue #491) — same
    /// reasoning as Android's `TtsSettingsSection` never listing `CLOUD` as a bare
    /// radio option: picking it here, with no provider/key/voice configured, would
    /// silently do nothing useful (`CloudTtsEngine`'s own gate just falls back to
    /// on-device every time). `CloudVoicesSection`'s own "Use Cloud Voices for Read
    /// Aloud" toggle is the only path that should set `ttsEngineType = .cloud`, gated
    /// on actually being configured.
    private var ttsSection: some View {
        Section {
            if Self.showsCloudVoicesActiveLabel(engineType: appState.ttsEngineType) {
                // Issue #495: when `.cloud` is active, its bound selection doesn't
                // match any of the segmented Picker's own options below (`.cloud` is
                // deliberately excluded, see #491's doc comment above), which SwiftUI
                // renders as nothing highlighted — visually disagreeing with
                // `CloudVoicesSection`'s own "Use Cloud Voices" toggle right below it.
                // Swap to a fixed label instead of a picker with nothing selected.
                HStack {
                    Text("Engine")
                        .foregroundStyle(LibraVaultColor.onSurface)
                    Spacer()
                    Text(TTSEngineType.cloud.displayName)
                        .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                }
            } else {
                // "Engine", not "Voice" — was "Voice" until #506 added a real
                // per-voice picker below and needed the label back. This one
                // picks System/On-Device/(Cloud, handled above); the new row
                // picks a specific installed voice within System.
                Picker("Engine", selection: $appState.ttsEngineType) {
                    ForEach(TTSEngineType.allCases.filter { $0 != .cloud }, id: \.self) { type in
                        Text(type.displayName).tag(type)
                    }
                }
                .pickerStyle(.segmented)
            }

            if Self.showsSystemVoicePickerRow(engineType: appState.ttsEngineType) {
                NavigationLink(destination: SystemVoicePickerView(selectedVoiceIdentifier: $appState.selectedSystemVoiceIdentifier)) {
                    HStack {
                        Text("Voice")
                            .foregroundStyle(LibraVaultColor.onSurface)
                        Spacer()
                        Text(Self.systemVoiceDisplayName(for: appState.selectedSystemVoiceIdentifier))
                            .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                    }
                }
            }
        } header: {
            sectionHeader("Text-to-Speech")
        } footer: {
            Text("On-device voice (System/On-Device above) runs fully offline. Cloud Voices, below once subscribed, is the only other network activity this app can ever have, and only sends text to a vendor you explicitly configure.")
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

    /// The app's real version, read from the bundle's `CFBundleShortVersionString`
    /// (Xcode's `MARKETING_VERSION`) rather than hardcoded — this used to say a
    /// stale "3.0.0-alpha" that never matched what TestFlight/App Store Connect
    /// actually shipped, unlike Android's SettingsViewModel which already reads
    /// `versionName` from PackageManager at runtime. Falls back to "unknown" if
    /// the bundle info is somehow missing, which should never happen in practice.
    /// `static` so it's directly testable without standing up the view — see
    /// SettingsAppVersionTests.
    static var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "unknown"
    }

    private var aboutSection: some View {
        Section {
            HStack {
                Text("Version")
                    .foregroundStyle(LibraVaultColor.onSurface)
                Spacer()
                Text(Self.appVersion)
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
    /// "contact us" form to.
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

    /// Real Apple StoreKit 2 in-app purchases (`StoreKitBillingManager`) — the entire
    /// native purchase path on iOS, replacing the external `Link` to
    /// libravault.xyz/support.html this section used to render (Android's Play flavor
    /// keeps an equivalent native Play Billing path via its own, separate concurrent
    /// effort; Android's F-Droid flavor, which has no billing API to hang an IAP off,
    /// is the one place that external link still makes sense — but iOS has no
    /// F-Droid-style alternative distribution, so it always uses this native path).
    /// Same "donation/subscription only, nothing feature-gated" product decision as
    /// ever — see `StoreKitBillingManager`'s doc comment.
    private var supportSection: some View {
        Section {
            if appState.isSupporter {
                Text("★ You're a Supporter — thank you!")
                    .font(LibraVaultTypography.bodyMedium.weight(.semibold))
                    .foregroundStyle(LibraVaultColor.secondary)
            }
            Text("LibraVault is free — no ads, no tracking, no accounts. If this app brings you joy, consider supporting its development.")
                .font(LibraVaultTypography.bodySmall)
                .foregroundStyle(LibraVaultColor.onSurfaceVariant)

            if billingManager.productsAvailable {
                Button {
                    Task { await billingManager.purchaseSubscription() }
                } label: {
                    Text("Subscribe — \(billingManager.subscriptionProduct?.displayPrice ?? "$1")/mo")
                }
                .disabled(billingManager.isSubscribed)

                Button {
                    Task { await billingManager.purchaseOneTimeTip() }
                } label: {
                    Text("Send a one-time tip")
                }
            } else {
                // Deliberately no external fallback link here — see the type doc
                // comment above. App Store Connect has no product configured yet (no
                // banking/tax agreement signed), so there is nothing purchasable to
                // offer; this state resolves itself once that's set up, with no code
                // change needed.
                Text("Support options are coming soon.")
                    .font(LibraVaultTypography.bodySmall)
                    .foregroundStyle(LibraVaultColor.onSurfaceVariant)
            }
        } header: {
            sectionHeader("Support Development")
        }
    }

    /// Whether `ttsSection` should render its fixed "Cloud Voices" label instead of
    /// the on-device engine Picker — issue #495. Extracted as a pure `static`
    /// predicate so it's directly testable without standing up SwiftUI, matching
    /// this file's own `appVersion` precedent (see `SettingsAppVersionTests`).
    static func showsCloudVoicesActiveLabel(engineType: TTSEngineType) -> Bool {
        engineType == .cloud
    }

    /// Whether `ttsSection` should show the per-voice picker row (#506) — only
    /// meaningful for `.system`; Pocket ships exactly one voice (its own
    /// section elsewhere handles that), and Cloud picks a voice via its own
    /// provider config in `CloudVoicesSection`. Extracted as a pure `static`
    /// predicate for the same testability reason as `showsCloudVoicesActiveLabel`.
    static func showsSystemVoicePickerRow(engineType: TTSEngineType) -> Bool {
        engineType == .system
    }

    /// Display text for the "Voice" row's trailing label — the voice's own
    /// name if the stored identifier still resolves to an installed voice,
    /// "Automatic" for no selection (nil), or "Automatic" for a stale
    /// identifier (e.g. a language pack removed since it was picked) rather
    /// than showing raw garbage - matches `TTSEngineBridge.resolvedVoice`'s
    /// same "degrade to automatic" behavior on the speaking side.
    static func systemVoiceDisplayName(for identifier: String?) -> String {
        guard let identifier, let voice = AVSpeechSynthesisVoice(identifier: identifier) else {
            return "Automatic"
        }
        return voice.name
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
            answer: "In Settings → Folders, tap \"Add Folder\" and pick a folder. LibraVault reads every EPUB, PDF, and Markdown file inside it — nothing is copied off your device."
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
            answer: "No. LibraVault works fully offline — no accounts, no cloud sync, no tracking. Everything, including on-device Read Aloud, runs locally. The one opt-in exception is Cloud Voices (Settings → Cloud Voices, subscribers only): if you turn it on and configure your own API key for a cloud TTS vendor, the text you choose to read aloud is sent to that vendor. It's off by default and stays off unless you explicitly enable it."
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
    .environmentObject(EncryptedVaultRuntime())
    .environmentObject(StoreKitBillingManager())
}
