import Foundation

/// Turns a `Bookmark`'s raw `position` string into what `BookmarksSheet` actually
/// shows a reader. `position` is an internal locator, not display text: it's the
/// same string `ReaderView.addBookmark()` writes and `navigateToBookmark(_:)` parses
/// to jump back to a spot, in whichever format is cheapest to store and most stable
/// for that format (a character offset for EPUB — see `ReaderView.addBookmark`'s doc
/// comment on why a page *index* isn't stable enough to store directly, a scroll
/// fraction for Markdown, a 1-based page number for PDF). None of those are meant to
/// be read by a person verbatim — `BookmarksSheet` used to display `position`
/// directly, which showed a raw `"Locator:0:0"` to the reader instead of anything
/// resembling "Chapter 1" once EPUB's bookmark format moved off a bare chapter
/// number (issue #331 / PR #333 round 2 QA).
///
/// A pure string→string function so it's independently unit-testable without
/// standing up `BookmarksSheet`'s view hierarchy — see `BookmarkPositionFormatterTests`.
enum BookmarkPositionFormatter {
    /// - `"Locator:<chapterIndex>:<charOffset>"` (current EPUB format) → `"Chapter N"`
    ///   (1-based). Deliberately chapter-only, not chapter+page: resolving a page
    ///   number needs the book's real `TextPaginator` output, which `BookmarksSheet`
    ///   has no access to (it only ever sees the stored `Bookmark`, not a loaded
    ///   `ReaderView`) — and "Chapter N" is exactly what this format replaced, so it's
    ///   also what keeps the pre-existing `testBookmarksSheetShowsAddedBookmark` UI
    ///   test's `"Chapter 1"` assertion valid for a bookmark added at the very start
    ///   of chapter 1.
    /// - `"Chapter N"` (legacy pre-#331 EPUB format) and `"Page N"` (PDF) are already
    ///   human-readable as written — passed through unchanged.
    /// - `"scroll:<fraction>"` (Markdown) has no meaningful integer to show — a
    ///   generic label beats printing a raw float.
    /// - Anything unrecognized (a future format, or a malformed string) is passed
    ///   through as-is rather than hidden — showing something odd is safer than
    ///   showing nothing for a bookmark that otherwise still works for navigation.
    static func displayText(for position: String) -> String {
        if position.hasPrefix("Locator:") {
            let components = position.dropFirst("Locator:".count).split(separator: ":")
            if let chapterIndexComponent = components.first, let chapterIndex = Int(chapterIndexComponent) {
                return "Chapter \(chapterIndex + 1)"
            }
            return position
        }
        if position.hasPrefix("scroll:") {
            return "Bookmark"
        }
        return position
    }
}
