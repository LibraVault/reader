import SwiftUI

struct SettingsView: View {
    @State private var enableLogging = false
    @State private var enableTTS = true
    @State private var fontSize: Double = 1.0

    var body: some View {
        NavigationStack {
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
    @State private var logs: String = "Logs will appear here...\n\n[Log integration with core:logger pending]"

    var body: some View {
        VStack {
            ScrollView {
                Text(logs)
                    .font(.system(.caption, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
            }
            .background(Color(.systemGray6))

            HStack {
                Button(action: { logs = "" }) {
                    Label("Clear", systemImage: "trash")
                }

                Spacer()

                Button(action: { }) {
                    Label("Share", systemImage: "square.and.arrow.up")
                }
            }
            .padding()
        }
        .navigationTitle("Logs")
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
    SettingsView()
}
