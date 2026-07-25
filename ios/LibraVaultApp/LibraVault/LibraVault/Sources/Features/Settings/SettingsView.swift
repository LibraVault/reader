import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var appState: AppState
    @State private var enableLogging = false

    private let skipDurationPresets: [Double] = [10, 15, 30, 45, 60]

    var body: some View {
        Form {
            vaultsSection
            readingSection
            playbackSection
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
            // TODO: Integrate with core:storage for real vault locations — same
            // Phase D gap as DomainBridge.swift's scanLibrary(vaultPath:) hardcoding
            // "/Documents".
            HStack {
                Image(systemName: "folder.fill")
                    .foregroundStyle(LibraVaultColor.primary)
                Text("/Documents")
                    .foregroundStyle(LibraVaultColor.onSurface)
                Spacer()
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(LibraVaultColor.primary)
            }
            Button(action: {}) {
                Label("Add Vault", systemImage: "plus.circle")
            }
            .foregroundStyle(LibraVaultColor.primary)
        } header: {
            sectionHeader("Vaults")
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
                    Text("\(String(format: "%.2g", appState.playbackSpeed))×")
                        .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                }
                Slider(value: $appState.playbackSpeed, in: 0.5...3.0, step: 0.25)
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
        } header: {
            sectionHeader("Playback")
        }
    }

    // MARK: - Privacy & Diagnostics

    private var privacySection: some View {
        Section {
            Toggle("Local crash logging", isOn: $enableLogging)
                .tint(LibraVaultColor.primary)
            // TODO: Integrate with core:logger
            if enableLogging {
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
    @State private var logs: String = "[Phase B: Log viewer ready for Phase C integration with core:logger]"
    @State private var isLoading = false

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
                Button(action: { logs = "" }) {
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
    }

    private func refreshLogs() {
        logs = """
        [LibraVault Diagnostic Logs]
        Version: 3.0.0-alpha
        Platform: iOS 17+

        [Phase B] Library initialized with mock data
        [Phase B] Domain bridge ready for Phase C KMP integration
        [Phase B] Log viewer functional - Phase C will integrate core:logger

        Phase C TODO:
        - Integrate actual core:logger for persistent logging
        - Wire up TTS state tracking
        - Add database access logging
        - Implement real file I/O logging
        """
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
