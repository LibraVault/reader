import SwiftUI

/// Vault counterpart to `BookmarksSheet` — reads from a `[VaultBookmark]`
/// passed in directly rather than `LibravaultDomainBridge.shared`, since
/// vault bookmarks live in the encrypted manifest, not the app's plaintext
/// bookmark store. A separate, parallel sheet rather than a reused/shared
/// one, matching `VaultStore`'s own "no shared interface with the non-vault
/// repositories" pattern (see its doc comment).
struct VaultBookmarksSheet: View {
    let bookmarks: [VaultBookmark]
    var onNavigate: (VaultBookmark) -> Void = { _ in }
    var onDelete: (VaultBookmark) -> Void = { _ in }
    var onEditNote: (VaultBookmark, String?) -> Void = { _, _ in }

    @State private var editingBookmark: VaultBookmark?
    @State private var draftNote: String = ""

    private var sorted: [VaultBookmark] {
        bookmarks.sorted { $0.createdAtEpochMillis < $1.createdAtEpochMillis }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Bookmarks")
                .font(LibraVaultTypography.headlineSmall)
                .foregroundStyle(LibraVaultColor.onSurface)
                .padding(LibraVaultSpacing.lg)

            if sorted.isEmpty {
                Text("No bookmarks yet. Tap the bookmark icon while reading to add one.")
                    .font(LibraVaultTypography.bodyMedium)
                    .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                    .padding(.horizontal, LibraVaultSpacing.lg)
                Spacer()
            } else {
                List(sorted, id: \.id) { bookmark in
                    HStack {
                        Image(systemName: "bookmark.fill")
                            .foregroundStyle(LibraVaultColor.primary)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(VaultPositionFormatter.displayText(for: bookmark.positionRef))
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
                        Button(role: .destructive) { onDelete(bookmark) } label: {
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

    private func beginEditing(_ bookmark: VaultBookmark) {
        draftNote = bookmark.note ?? ""
        editingBookmark = bookmark
    }

    private func saveNote() {
        guard let bookmark = editingBookmark else { return }
        onEditNote(bookmark, draftNote)
    }
}

/// Turns a `VaultBookmark`/`VaultHighlight` `positionRef` into display text —
/// these are internal locators (`"chapter:N"`, `"page:N"`, `"ms:N"`), never
/// meant to be shown verbatim, mirroring why `BookmarkPositionFormatter`
/// exists for the non-vault equivalent (issue #331).
enum VaultPositionFormatter {
    static func displayText(for positionRef: String) -> String {
        if positionRef.hasPrefix("page:"), let page = Int(positionRef.dropFirst("page:".count)) {
            return "Page \(page + 1)"
        }
        if positionRef.hasPrefix("chapter:"), let chapter = Int(positionRef.dropFirst("chapter:".count)) {
            return "Chapter \(chapter + 1)"
        }
        if positionRef.hasPrefix("ms:"), let ms = Int64(positionRef.dropFirst("ms:".count)) {
            return formatDuration(ms: ms)
        }
        return positionRef
    }

    private static func formatDuration(ms: Int64) -> String {
        let totalSeconds = ms / 1000
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        }
        return String(format: "%d:%02d", minutes, seconds)
    }
}
