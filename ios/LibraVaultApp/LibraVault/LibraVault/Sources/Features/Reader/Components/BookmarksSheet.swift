import SwiftUI

/// Mirrors Android's BookmarksSheet (feature/reader/components) — a real list backed by
/// LibravaultDomainBridge.bookmarks (previously only reachable via "Add Bookmark" in an
/// overflow menu, with no way to ever see what you'd bookmarked) plus inline note editing.
struct BookmarksSheet: View {
    let bookId: String
    var onNavigate: (Bookmark) -> Void = { _ in }
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
                Text("No bookmarks yet. Long-press the bookmark icon while reading to add one.")
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
                            // bookmark.position is an internal locator (see
                            // BookmarkPositionFormatter's doc comment), not display
                            // text — showing it verbatim used to leak raw strings
                            // like "Locator:0:0" once EPUB's bookmark format moved
                            // off a bare chapter number (issue #331).
                            Text(BookmarkPositionFormatter.displayText(for: bookmark.position))
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
                    .contentShape(Rectangle())
                    .onTapGesture { onNavigate(bookmark) }
                    .listRowBackground(LibraVaultColor.surface)
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) { deleteBookmark(bookmark) } label: {
                            Label("Delete", systemImage: "trash")
                        }
                    }
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

    private func deleteBookmark(_ bookmark: Bookmark) {
        Task {
            try? await bridge.deleteBookmark(bookId: bookId, bookmarkId: bookmark.id)
        }
    }
}

#Preview {
    BookmarksSheet(bookId: "1")
}
