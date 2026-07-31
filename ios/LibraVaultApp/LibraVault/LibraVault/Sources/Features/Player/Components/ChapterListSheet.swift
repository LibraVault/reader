import SwiftUI

/// Mirrors Android's ChapterListSheet (feature/player/components). Chapter titles are
/// passed in rather than read from MockChapterContent directly, so the sheet shows
/// real chapters for books with one (see AppState.nowPlayingChapterTitles).
struct ChapterListSheet: View {
    let currentChapter: Int
    let chapterTitles: [String]
    let onSelect: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Chapters")
                .font(LibraVaultTypography.headlineSmall)
                .foregroundStyle(LibraVaultColor.onSurface)
                .padding(LibraVaultSpacing.lg)

            List(1...chapterTitles.count, id: \.self) { chapter in
                Button(action: { onSelect(chapter) }) {
                    HStack {
                        Text(chapterTitles[chapter - 1])
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
    ChapterListSheet(currentChapter: 2, chapterTitles: ["Chapter 1", "Chapter 2", "Chapter 3"], onSelect: { _ in })
}
