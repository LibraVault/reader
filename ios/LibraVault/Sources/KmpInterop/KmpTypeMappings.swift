import Foundation

// MARK: - KMP Type Mappings
// Phase D: Swift↔Kotlin type conversion layer
//
// These mappings enable Swift UI to work with Kotlin domain models
// Each mapping includes:
// - Swift model (used in UI)
// - Mapping function from Kotlin
// - Mapping function to Kotlin
//
// Kotlin types (from core:domain):
// - LibraryItem: represents a book/media with metadata
// - ReadingProgress: tracks position in a book
// - Bookmark: user-created bookmark in a book
// - Highlight: user-created highlighted text with note
//
// Usage (Phase D):
// ```swift
// let kotlinItem: LibraryItem = kmpDomain.getLibrary()[0]
// let swiftBook = mapToBookData(kotlinItem)
// ```

// MARK: - BookData (LibraryItem ↔ BookData)

/// Swift model for books, mapped from Kotlin LibraryItem
struct BookDataModel: Identifiable {
    let id: String
    let title: String
    let author: String
    let format: MediaFormat
    let pageCount: Int?
    let durationMs: Int?       // for audiobooks
    let coverArtPath: String?
    let progress: Double       // from ReadingProgress
    var highlights: [Highlight] = []
    var bookmarks: [Bookmark] = []

    // Phase D: Will be created from Kotlin LibraryItem + ReadingProgress
    // init(from kotlinItem: LibraryItem, progress: ReadingProgress) {
    //     self.id = String(kotlinItem.id)
    //     self.title = kotlinItem.title
    //     self.author = kotlinItem.author
    //     self.format = mapMediaFormat(kotlinItem.format)
    //     self.pageCount = kotlinItem.pageCount
    //     self.durationMs = kotlinItem.durationMs
    //     self.coverArtPath = kotlinItem.coverArtPath
    //     let progressValue = if let page = progress.pageIndex {
    //         Double(page) / Double(pageCount ?? 1)
    //     } else {
    //         0.0
    //     }
    //     self.progress = progressValue
    // }
}

// MARK: - MediaFormat Mapping

/// Swift enum mapped from Kotlin MediaFormat
enum MediaFormatSwift {
    case epub
    case pdf
    case mp3        // audiobook
    case m4b        // audiobook
    case ogg        // audiobook
    case flac       // audiobook
    case opus       // audiobook
    case aac        // audiobook

    var displayName: String {
        switch self {
        case .epub: return "EPUB"
        case .pdf: return "PDF"
        case .mp3: return "MP3"
        case .m4b: return "M4B (Audiobook)"
        case .ogg: return "OGG"
        case .flac: return "FLAC"
        case .opus: return "OPUS"
        case .aac: return "AAC"
        }
    }

    var isAudio: Bool {
        switch self {
        case .mp3, .m4b, .ogg, .flac, .opus, .aac:
            return true
        default:
            return false
        }
    }

    // Phase D: Mapping function from Kotlin
    // static func from(_ kotlin: xyz.libravault.core.domain.model.MediaFormat) -> MediaFormatSwift {
    //     switch kotlin {
    //     case .EPUB: return .epub
    //     case .PDF: return .pdf
    //     case .MP3: return .mp3
    //     // ... etc
    //     }
    // }
}

// MARK: - ReadingProgress Mapping

/// Swift model for reading progress, mapped from Kotlin ReadingProgress
struct ReadingProgressModel {
    let itemId: String
    let positionCfi: String?   // EPUB CFI (IDPF standard)
    let pageIndex: Int?        // PDF page number
    let lastReadAt: Date
    let progress: Double       // calculated: pageIndex / totalPages or CFI offset

    // Phase D: Will be created from Kotlin ReadingProgress
    // init(from kotlin: xyz.libravault.core.domain.model.ReadingProgress) {
    //     self.itemId = String(kotlin.itemId)
    //     self.positionCfi = kotlin.positionCfi
    //     self.pageIndex = kotlin.pageIndex
    //     self.lastReadAt = Date(timeIntervalSince1970: Double(kotlin.lastReadAt.toEpochMilliseconds()) / 1000)
    //     // progress calculation depends on total page count
    // }

    // Phase D: Convert back to Kotlin for saving
    // func toKotlin(totalPages: Int) -> xyz.libravault.core.domain.model.ReadingProgress {
    //     let pageIdx = pageIndex ?? Int(Double(totalPages) * progress)
    //     return xyz.libravault.core.domain.model.ReadingProgress(
    //         itemId: Int64(itemId) ?? 0,
    //         positionCfi: positionCfi,
    //         pageIndex: pageIdx,
    //         lastReadAt: Instant.now()
    //     )
    // }
}

// MARK: - Bookmark Mapping

/// Swift model for bookmarks, mapped from Kotlin Bookmark
struct BookmarkModel: Identifiable {
    let id: String
    let position: String              // CFI for EPUB, "page:N" for PDF, "ms:N" for audio
    let label: String?
    let note: String?
    let createdAt: Date

    // Phase D: Mapping from Kotlin
    // init(from kotlin: xyz.libravault.core.domain.model.Bookmark) {
    //     self.id = String(kotlin.id)
    //     self.position = kotlin.positionRef
    //     self.label = kotlin.label
    //     self.note = kotlin.note
    //     self.createdAt = Date(timeIntervalSince1970: Double(kotlin.createdAt.toEpochMilliseconds()) / 1000)
    // }
}

// MARK: - Highlight Mapping

/// Swift model for highlights, mapped from Kotlin Highlight
struct HighlightModel: Identifiable {
    let id: String
    let position: String       // CFI/page/timestamp
    let text: String
    let colorHex: String
    let note: String?
    let createdAt: Date

    // Phase D: Mapping from Kotlin
    // init(from kotlin: xyz.libravault.core.domain.model.Highlight) {
    //     self.id = String(kotlin.id)
    //     self.position = kotlin.positionRef
    //     self.text = kotlin.highlightedText
    //     self.colorHex = kotlin.colorHex
    //     self.note = kotlin.note
    //     self.createdAt = Date(timeIntervalSince1970: Double(kotlin.createdAt.toEpochMilliseconds()) / 1000)
    // }
}

// MARK: - Collection Mapping

/// Swift model for collections, mapped from Kotlin Collection
struct CollectionModel: Identifiable {
    let id: String
    let name: String
    let createdAt: Date
    let updatedAt: Date
    let items: [String] = []   // book IDs in collection

    // Phase D: Mapping from Kotlin
    // init(from kotlin: xyz.libravault.core.domain.model.Collection) {
    //     self.id = String(kotlin.id)
    //     self.name = kotlin.name
    //     self.createdAt = Date(timeIntervalSince1970: Double(kotlin.createdAt.toEpochMilliseconds()) / 1000)
    //     self.updatedAt = Date(timeIntervalSince1970: Double(kotlin.updatedAt.toEpochMilliseconds()) / 1000)
    // }
}

// MARK: - Global Mapping Functions

/// Maps Kotlin LibraryItem to Swift BookData
/// Phase D: Will import actual Kotlin types
func mapToBookData(_ item: BookDataModel, progress: Double = 0.0) -> BookData {
    return BookData(
        id: item.id,
        title: item.title,
        author: item.author,
        format: item.format,
        progress: progress,
        highlights: item.highlights,
        bookmarks: item.bookmarks
    )
}

/// Maps Kotlin MediaFormat to Swift MediaFormat
/// Phase D: Will import actual Kotlin MediaFormat enum
func mapMediaFormat(_ format: MediaFormat) -> MediaFormatSwift {
    switch format {
    case .epub: return .epub
    case .pdf: return .pdf
    case .mobi: return .pdf  // treat as PDF fallback
    case .cbz: return .pdf   // treat as PDF fallback
    }
}

/// Maps Swift BookmarkModel to Kotlin Bookmark
/// Phase D: Will export to Kotlin Bookmark DTO
func mapToKotlinBookmark(_ bookmark: Bookmark, itemId: Int64) -> String {
    // Returns JSON for serialization or Kotlin object
    return """
    {
      "id": "\(bookmark.id)",
      "itemId": \(itemId),
      "positionRef": "\(bookmark.position)",
      "note": "\(bookmark.note ?? "")",
      "createdAt": \(Int64(bookmark.createdAt.timeIntervalSince1970 * 1000))
    }
    """
}

/// Maps Swift HighlightModel to Kotlin Highlight
/// Phase D: Will export to Kotlin Highlight DTO
func mapToKotlinHighlight(_ highlight: Highlight, itemId: Int64) -> String {
    // Returns JSON for serialization or Kotlin object
    return """
    {
      "id": "\(highlight.id)",
      "itemId": \(itemId),
      "positionRef": "\(highlight.position)",
      "highlightedText": "\(highlight.text.replacingOccurrences(of: "\"", with: "\\\""))",
      "colorHex": "\(highlight.colorHex)",
      "note": "\(highlight.note ?? "")",
      "createdAt": \(Int64(highlight.createdAt.timeIntervalSince1970 * 1000))
    }
    """
}
