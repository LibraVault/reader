import SwiftUI
import Foundation

@MainActor
final class AppState: ObservableObject {
    @Published var books: [BookItem] = []
    @Published var isLoading = false
    @Published var error: AppError?

    private let bridge = LibravaultDomainBridge.shared

    init() {
        Task {
            try? await bridge.initialize()
            books = bridge.allBooks.map { BookItem(from: $0) }
        }
    }

    func loadLibrary() async {
        isLoading = true
        defer { isLoading = false }

        do {
            let libraryBooks = try await bridge.scanLibrary(vaultPath: "/Documents")
            books = libraryBooks.map { BookItem(from: $0) }
            bridge.log("Loaded \(books.count) books from library", tag: "Library")
        } catch let err as DomainError {
            error = AppError.libraryLoadFailed(err.localizedDescription)
        } catch {
            self.error = AppError.libraryLoadFailed(error.localizedDescription)
        }
    }

    func clearError() {
        error = nil
    }
}

struct BookItem: Identifiable {
    let id: String
    let title: String
    let author: String
    let coverUrl: String?
    var progress: Double

    init(id: String, title: String, author: String, coverUrl: String? = nil, progress: Double = 0.0) {
        self.id = id
        self.title = title
        self.author = author
        self.coverUrl = coverUrl
        self.progress = progress
    }

    init(from bookData: BookData) {
        self.id = bookData.id
        self.title = bookData.title
        self.author = bookData.author
        self.coverUrl = nil
        self.progress = bookData.progress
    }
}

enum AppError: LocalizedError {
    case libraryLoadFailed(String)
    case bookNotFound
    case storageAccessDenied

    var errorDescription: String? {
        switch self {
        case .libraryLoadFailed(let reason):
            return "Failed to load library: \(reason)"
        case .bookNotFound:
            return "Book not found"
        case .storageAccessDenied:
            return "Storage access denied"
        }
    }
}
