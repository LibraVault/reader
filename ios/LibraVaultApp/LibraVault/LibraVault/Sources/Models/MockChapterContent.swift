import Foundation

/// The 5 sample chapters used by both Reader (paginated/scrolling text) and Player
/// (TTS narration + chapter list) — extracted so both features read from one source
/// instead of ReaderView keeping a private copy Player would otherwise duplicate.
enum MockChapterContent {
    static let chapters: [String] = [
        "Chapter 1: The Beginning\n\nIt was a bright cold day in April, and the clocks were striking thirteen. The city stretched before them, vast and incomprehensible, full of secrets and mysteries waiting to be discovered.",
        "Chapter 2: Into the Depths\n\nThey ventured deeper into the ancient library, their footsteps echoing against stone walls. The air grew colder as they descended, and the books seemed to watch their progress with silent judgment.",
        "Chapter 3: The Discovery\n\nAmong the forgotten volumes, they found it—a manuscript bound in leather, its pages yellowed with age. The words seemed to shimmer, as if alive with their own peculiar power.",
        "Chapter 4: Revelations\n\nAs they read, the truth began to unfold. Every sentence was a thread, weaving together into a tapestry of understanding. What they had thought was lost was merely hidden, waiting for someone brave enough to seek it.",
        "Chapter 5: The Choice\n\nNow came the moment of decision. Would they close the book and return to their ordinary lives, or would they follow the path laid out before them, into territories unknown?",
    ]

    static func text(for chapter: Int) -> String {
        chapters[(chapter - 1) % chapters.count]
    }

    static var count: Int { chapters.count }

    /// A short title extracted from each chapter's first line, for the chapter list sheet.
    static func title(for chapter: Int) -> String {
        text(for: chapter).components(separatedBy: "\n").first ?? "Chapter \(chapter)"
    }
}
