import SwiftUI

/// Table of contents sheet for the Markdown reader — the first TOC UI in the app
/// (neither EPUB nor PDF has one). Mirrors BookmarksSheet's presentation shape;
/// indentation reflects each entry's heading level (H1..H6), same as the Android
/// MarkdownTocSheet composable.
struct MarkdownTocSheet: View {
    let entries: [MarkdownTocEntry]
    let onEntryTap: (MarkdownTocEntry) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Contents")
                .font(LibraVaultTypography.headlineSmall)
                .foregroundStyle(LibraVaultColor.onSurface)
                .padding(LibraVaultSpacing.lg)

            if entries.isEmpty {
                Text("No headings found in this document.")
                    .font(LibraVaultTypography.bodyMedium)
                    .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                    .padding(.horizontal, LibraVaultSpacing.lg)
                Spacer()
            } else {
                List(entries) { entry in
                    Text(entry.title)
                        .font(entry.level == 1 ? LibraVaultTypography.bodyMedium.weight(.semibold) : LibraVaultTypography.bodyMedium)
                        .foregroundStyle(entry.level <= 2 ? LibraVaultColor.onSurface : LibraVaultColor.onSurfaceVariant)
                        .padding(.leading, CGFloat(entry.level - 1) * 16)
                        .listRowBackground(LibraVaultColor.surface)
                        .contentShape(Rectangle())
                        .onTapGesture { onEntryTap(entry) }
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
    MarkdownTocSheet(
        entries: [
            MarkdownTocEntry(level: 1, title: "Introduction", blockIndex: 0),
            MarkdownTocEntry(level: 2, title: "Background", blockIndex: 2),
            MarkdownTocEntry(level: 1, title: "Conclusion", blockIndex: 5),
        ],
        onEntryTap: { _ in }
    )
}
