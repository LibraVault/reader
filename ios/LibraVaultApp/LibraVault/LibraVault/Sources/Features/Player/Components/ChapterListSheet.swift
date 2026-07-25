import SwiftUI

/// Mirrors Android's ChapterListSheet (feature/player/components).
struct ChapterListSheet: View {
    let currentChapter: Int
    let onSelect: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Chapters")
                .font(LibraVaultTypography.headlineSmall)
                .foregroundStyle(LibraVaultColor.onSurface)
                .padding(LibraVaultSpacing.lg)

            List(1...MockChapterContent.count, id: \.self) { chapter in
                Button(action: { onSelect(chapter) }) {
                    HStack {
                        Text(MockChapterContent.title(for: chapter))
                            .font(LibraVaultTypography.bodyMedium)
                            .foregroundStyle(LibraVaultColor.onSurface)
                        Spacer()
                        if chapter == currentChapter {
                            Image(systemName: "speaker.wave.2.fill")
                                .foregroundStyle(LibraVaultColor.primary)
                        }
                    }
                }
                .listRowBackground(LibraVaultColor.surface)
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        }
        .background(LibraVaultColor.surface)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}

#Preview {
    ChapterListSheet(currentChapter: 2, onSelect: { _ in })
}
