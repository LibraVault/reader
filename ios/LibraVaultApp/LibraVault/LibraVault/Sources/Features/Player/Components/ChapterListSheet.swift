import SwiftUI

/// Mirrors Android's ChapterListSheet (feature/player/components). Chapter titles are
/// passed in — see AppState.nowPlayingChapterTitles for real chapter titles vs. the
/// single-title audiobook case.
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

            if chapterTitles.isEmpty {
                // Reachable if content failed to load for the now-playing book — an
                // honest empty state, not a crash from `1...0` (chapterTitles.count).
                Text("No chapters available")
                    .font(LibraVaultTypography.bodyMedium)
                    .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                    .padding(LibraVaultSpacing.lg)
            } else {
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
        }
        .background(LibraVaultColor.surface)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}

#Preview {
    ChapterListSheet(currentChapter: 2, chapterTitles: ["Chapter 1", "Chapter 2", "Chapter 3"], onSelect: { _ in })
}
