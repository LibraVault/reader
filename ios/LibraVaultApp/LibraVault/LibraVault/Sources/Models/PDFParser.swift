import Foundation
import PDFKit

/// Extracts real page text from a PDF via PDFKit. Each page becomes one "chapter" —
/// PDFs don't have EPUB-style semantic chapters, and treating each page as a chapter
/// keeps parity with the existing paginated Reader UI ("Page X of Y") without a
/// separate pagination model.
enum PDFParser {
    enum ParseError: Error, Equatable {
        case invalidDocument
        case emptyDocument
    }

    static func parse(fileURL: URL) throws -> [BookChapter] {
        guard let document = PDFDocument(url: fileURL) else {
            throw ParseError.invalidDocument
        }
        guard document.pageCount > 0 else {
            throw ParseError.emptyDocument
        }

        return (0..<document.pageCount).map { index in
            let text = document.page(at: index)?.string?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return BookChapter(title: "Page \(index + 1)", text: text)
        }
    }
}
