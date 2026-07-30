import Foundation

/// Loads/saves bookmarks, highlights, and reading progress to UserDefaults. Without
/// this, LibravaultDomainBridge's `bookmarks`/`highlights`/`progress` dictionaries
/// were pure in-memory state on a process-lifetime singleton — anything added was lost
/// on every relaunch even though the UI (BookmarksSheet, "Add Bookmark", the reading
/// progress bar) presents them as saved. Kept separate from the bridge, like
/// VaultPersistence is kept separate from AppState, so it's testable against an
/// isolated UserDefaults suite.
struct ReadingDataPersistence {
    private let defaults: UserDefaults

    private enum Key {
        static let bookmarks = "xyz.libravault.bookmarks"
        static let highlights = "xyz.libravault.highlights"
        static let progress = "xyz.libravault.progress"
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func loadBookmarks() -> [String: [Bookmark]] {
        load(Key.bookmarks) ?? [:]
    }

    func save(bookmarks: [String: [Bookmark]]) {
        save(bookmarks, forKey: Key.bookmarks)
    }

    func loadHighlights() -> [String: [Highlight]] {
        load(Key.highlights) ?? [:]
    }

    func save(highlights: [String: [Highlight]]) {
        save(highlights, forKey: Key.highlights)
    }

    func loadProgress() -> [String: Double] {
        defaults.dictionary(forKey: Key.progress) as? [String: Double] ?? [:]
    }

    func save(progress: [String: Double]) {
        defaults.set(progress, forKey: Key.progress)
    }

    private func load<T: Decodable>(_ key: String) -> T? {
        guard let data = defaults.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    private func save<T: Encodable>(_ value: T, forKey key: String) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        defaults.set(data, forKey: key)
    }
}
