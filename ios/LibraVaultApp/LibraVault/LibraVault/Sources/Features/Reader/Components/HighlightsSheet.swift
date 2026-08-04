import SwiftUI

/// Read-only list backed by LibravaultDomainBridge.highlights. Not currently wired up
/// to any screen — its only caller, BookDetailView's "View Highlights" button, was
/// removed (LibraryView now navigates straight into Reader/Player, matching Android,
/// which has no book-detail screen at all). Kept for whenever a real in-reader
/// "create a highlight" affordance exists (ReaderView enables text selection but
/// nothing turns a selection into a Highlight yet) — until then this component has
/// nothing to ever show, since there's no way to populate it.
struct HighlightsSheet: View {
    let bookId: String
    @ObservedObject private var bridge = LibravaultDomainBridge.shared

    private var highlights: [Highlight] {
        (bridge.highlights[bookId] ?? []).sorted { $0.createdAt < $1.createdAt }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Highlights")
                .font(LibraVaultTypography.headlineSmall)
                .foregroundStyle(LibraVaultColor.onSurface)
                .padding(LibraVaultSpacing.lg)

            if highlights.isEmpty {
                Text("No highlights yet.")
                    .font(LibraVaultTypography.bodyMedium)
                    .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                    .padding(.horizontal, LibraVaultSpacing.lg)
                Spacer()
            } else {
                List(highlights) { highlight in
                    HStack(alignment: .top) {
                        Circle()
                            .fill(Color(hex: UInt32(highlight.colorHex, radix: 16) ?? 0xFFFF00))
                            .frame(width: 12, height: 12)
                            .padding(.top, 4)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(highlight.text)
                                .font(LibraVaultTypography.bodyMedium)
                                .foregroundStyle(LibraVaultColor.onSurface)
                            Text(highlight.position)
                                .font(LibraVaultTypography.bodySmall)
                                .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                            if let note = highlight.note, !note.isEmpty {
                                Text(note)
                                    .font(LibraVaultTypography.bodySmall)
                                    .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                                    .lineLimit(2)
                            }
                        }
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
    }
}

#Preview {
    HighlightsSheet(bookId: "1")
}
