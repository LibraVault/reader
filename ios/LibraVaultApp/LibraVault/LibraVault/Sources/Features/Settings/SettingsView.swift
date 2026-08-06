import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @EnvironmentObject var appState: AppState
    @State private var isPickingVaultFolder = false
    @State private var loggingEnabled: Bool
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
                }
            }
            .onDelete { offsets in
                for index in offsets {
                    appState.removeVault(appState.vaults[index])
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

    // MARK: - Support Development

    private var supportSection: some View {
        Section {
            if appState.isSupporter {
                Text("★ You're a Supporter — thank you!")
                    .font(LibraVaultTypography.bodyMedium.weight(.semibold))
                    .foregroundStyle(LibraVaultColor.secondary)
            }
            // No donate button here: Android's is backed by a real BTCPay-verified
            // BTC/XMR flow (SettingsScreen.kt's DonateSheet) — there's nothing honest
            // to wire that button to on iOS yet, and a button that does nothing when
            // tapped is worse than not having it.
            Text("LibraVault is free — no ads, no tracking, no accounts. If this app brings you joy, consider supporting its development.")
                .font(LibraVaultTypography.bodySmall)
                .foregroundStyle(LibraVaultColor.onSurfaceVariant)
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
    var body: some View {
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

            Spacer()

            Link("GitHub Repository", destination: URL(string: "https://github.com/LibraVault/reader")!)
                .font(LibraVaultTypography.bodySmall)
                .foregroundStyle(LibraVaultColor.primary)
                .padding()
        }
        .navigationTitle("About LibraVault")
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
