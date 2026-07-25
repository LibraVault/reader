import SwiftUI

struct SettingsView: View {
    @State private var enableLogging = false
    @State private var enableTTS = true
    @State private var fontSize: Double = 1.0

    var body: some View {
        Form {
            // Display Settings
            Section("Display") {
                HStack {
                    Text("Font Size")
                    Slider(value: $fontSize, in: 0.8...1.5, step: 0.1)
                }
            }

            // Audio Settings
            Section("Audio & Accessibility") {
                Toggle("Text-to-Speech", isOn: $enableTTS)
                // TODO: Integrate with core:tts
            }

            // Library Settings
            Section("Library") {
                NavigationLink(destination: LibrarySettingsView()) {
                    Text("Manage Vaults")
                }
            }

            // Debug / Logging
            Section("Developer") {
                Toggle("Enable Logging", isOn: $enableLogging)
                // TODO: Integrate with core:logger
                if enableLogging {
                    NavigationLink(destination: LogViewerView()) {
                        Text("View Logs")
                    }
                }
            }

            // About
            Section("About") {
                HStack {
                    Text("Version")
                    Spacer()
                    Text("3.0.0-alpha")
                        .foregroundColor(.secondary)
                }

                NavigationLink(destination: AboutView()) {
                    Text("About LibraVault")
                }
            }
        }
        .navigationTitle("Settings")
    }
}

struct LibrarySettingsView: View {
    var body: some View {
        VStack {
            Text("Manage Your Library Vaults")
                .font(.headline)
                .padding()

            // TODO: Integrate with core:storage to show vault locations
            List {
                Section("Added Vaults") {
                    HStack {
                        Image(systemName: "folder.fill")
                        Text("/Documents")
                        Spacer()
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundColor(.green)
                    }
                }

                Section {
                    Button(action: {}) {
                        HStack {
                            Image(systemName: "plus.circle")
                            Text("Add New Vault")
                        }
                    }
                }
            }
        }
        .navigationTitle("Manage Vaults")
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
                    .foregroundColor(.blue)

                Text("LibraVault")
                    .font(.title)
                    .fontWeight(.bold)

                Text("Your Personal E-Book Library")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            .padding()

            VStack(alignment: .leading, spacing: 12) {
                Text("About")
                    .font(.headline)

                Text("LibraVault is a privacy-first e-book reader and library manager for iOS, focused on giving you full control over your reading experience without tracking or data collection.")
                    .font(.body)
                    .foregroundColor(.secondary)
            }
            .padding()

            VStack(alignment: .leading, spacing: 12) {
                Text("Privacy")
                    .font(.headline)

                BulletPoint(text: "No cloud sync or accounts")
                BulletPoint(text: "All data stored locally")
                BulletPoint(text: "No tracking or analytics")
                BulletPoint(text: "Open source")
            }
            .padding()

            Spacer()

            Link("GitHub Repository", destination: URL(string: "https://github.com/LibraVault/reader")!)
                .font(.caption)
                .foregroundColor(.blue)
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
                .foregroundColor(.secondary)
        }
    }
}

#Preview {
    NavigationStack {
        SettingsView()
    }
}
