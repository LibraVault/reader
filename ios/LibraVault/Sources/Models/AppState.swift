import SwiftUI

@MainActor
final class AppState: ObservableObject {
    @Published var books: [BookItem] = []
    @Published var selectedBook: BookItem?
    @Published var isLoading = false
    @Published var error: AppError?

    // TODO: Integrate with Kotlin Multiplatform domain layer
    // - Connect to core:domain UseCases
    // - Bind to core:database repositories
    // - Use core:logger for diagnostics

    func loadLibrary() async {
        isLoading = true
        defer { isLoading = false }

        // TODO: Call KMP ScanVaultUseCase
        // For now: placeholder
        books = [
            BookItem(id: "1", title: "Sample Book", author: "Author", coverUrl: nil),
        ]
    }

    func selectBook(_ book: BookItem) {
        selectedBook = book
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
    var progress: Double = 0.0
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
