import Foundation

/// Opt-in, local-only diagnostic logger. Mirrors Android's LibravaultLogger
/// (core:logger): writes rotate at maxLogSizeBytes, are gated behind a
/// UserDefaults-backed `isEnabled` flag the user controls from Settings, and are
/// never transmitted anywhere. Replaces the Settings log viewer's previous behavior
/// of showing a hardcoded, fabricated string instead of anything actually logged.
struct LibraVaultLogStore {
    private let fileManager: FileManager
    private let defaults: UserDefaults
    private let logFileURL: URL
    private let archiveFileURL: URL
    private let maxLogSizeBytes: Int

    private static let loggingEnabledKey = "xyz.libravault.loggingEnabled"

    private static let timestampFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        return formatter
    }()

    var isEnabled: Bool {
        get { defaults.bool(forKey: Self.loggingEnabledKey) }
        nonmutating set { defaults.set(newValue, forKey: Self.loggingEnabledKey) }
    }

    init(
        fileManager: FileManager = .default,
        defaults: UserDefaults = .standard,
        directory: URL? = nil,
        maxLogSizeBytes: Int = 512 * 1024
    ) {
        self.fileManager = fileManager
        self.defaults = defaults
        self.maxLogSizeBytes = maxLogSizeBytes
        let dir = directory ?? fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]
        self.logFileURL = dir.appendingPathComponent("libravault.log")
        self.archiveFileURL = dir.appendingPathComponent("libravault.log.bak")
    }

    /// No-op when logging is disabled — matches Android's opt-in semantics, where
    /// nothing is written to disk until the user turns "Local crash logging" on.
    func write(level: String, tag: String, message: String) {
        guard isEnabled else { return }
        rotateIfNeeded()

        let timestamp = Self.timestampFormatter.string(from: Date())
        let line = "\(timestamp) [\(level)/\(tag)] \(message)\n"
        guard let data = line.data(using: .utf8) else { return }

        if fileManager.fileExists(atPath: logFileURL.path), let handle = try? FileHandle(forWritingTo: logFileURL) {
            defer { try? handle.close() }
            handle.seekToEndOfFile()
            handle.write(data)
        } else {
            try? data.write(to: logFileURL)
        }
    }

    /// Returns log contents for user-initiated sharing/inspection (LogViewerView).
    func readLogs() -> String {
        (try? String(contentsOf: logFileURL, encoding: .utf8)) ?? "No logs recorded."
    }

    func clearLogs() {
        try? fileManager.removeItem(at: logFileURL)
    }

    private func rotateIfNeeded() {
        guard let attributes = try? fileManager.attributesOfItem(atPath: logFileURL.path),
              let size = attributes[.size] as? Int,
              size > maxLogSizeBytes else { return }
        try? fileManager.removeItem(at: archiveFileURL)
        try? fileManager.copyItem(at: logFileURL, to: archiveFileURL)
        try? fileManager.removeItem(at: logFileURL)
    }
}
