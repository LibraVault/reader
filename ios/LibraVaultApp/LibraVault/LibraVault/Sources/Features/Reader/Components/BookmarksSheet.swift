import SwiftUI

/// Mirrors Android's BookmarksSheet (feature/reader/components) — a real list backed by
/// LibravaultDomainBridge.bookmarks (previously only reachable via "Add Bookmark" in an
/// overflow menu, with no way to ever see what you'd bookmarked) plus inline note editing.
struct BookmarksSheet: View {
    let bookId: String
    @ObservedObject private var bridge = LibravaultDomainBridge.shared
    @State private var editingBookmark: Bookmark?
    @State private var draftNote: String = ""

    private var bookmarks: [Bookmark] {
        (bridge.bookmarks[bookId] ?? []).sorted { $0.createdAt < $1.createdAt }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Bookmarks")
                .font(LibraVaultTypography.headlineSmall)
                .foregroundStyle(LibraVaultColor.onSurface)
                .padding(LibraVaultSpacing.lg)

            if bookmarks.isEmpty {
                Text("No bookmarks yet. Tap the bookmark icon while reading to add one.")
                    .font(LibraVaultTypography.bodyMedium)
                    .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                    .padding(.horizontal, LibraVaultSpacing.lg)
                Spacer()
            } else {
                List(bookmarks) { bookmark in
                    HStack {
                        Image(systemName: "bookmark.fill")
                            .foregroundStyle(LibraVaultColor.primary)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(bookmark.position)
                                .font(LibraVaultTypography.bodyMedium)
                                .foregroundStyle(LibraVaultColor.onSurface)
                            if let note = bookmark.note, !note.isEmpty {
                                Text(note)
                                    .font(LibraVaultTypography.bodySmall)
                                    .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                                    .lineLimit(2)
                            }
                        }
                        Spacer()
                        Button(action: { beginEditing(bookmark) }) {
                            Image(systemName: "pencil")
                                .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                        }
                        .buttonStyle(.plain)
                    }
                    .listRowBackground(LibraVaultColor.surface)
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
        .background(LibraVaultColor.surface)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .alert("Edit note", isPresented: editingBinding) {
            TextField("Note", text: $draftNote)
            Button("Cancel", role: .cancel) {}
            Button("Save") { saveNote() }
        }
    }

    private var editingBinding: Binding<Bool> {
        Binding(get: { editingBookmark != nil }, set: { if !$0 { editingBookmark = nil } })
    }

    private func beginEditing(_ bookmark: Bookmark) {
        draftNote = bookmark.note ?? ""
        editingBookmark = bookmark
    }

    private func saveNote() {
        guard let bookmark = editingBookmark else { return }
        Task {
            try? await bridge.updateBookmarkNote(bookId: bookId, bookmarkId: bookmark.id, note: draftNote)
        }
    }
}

#Preview {
    BookmarksSheet(bookId: "1")
}
