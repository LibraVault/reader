package xyz.libravault.core.domain.usecase

import kotlinx.coroutines.flow.Flow
import xyz.libravault.core.domain.model.Bookmark
import xyz.libravault.core.domain.model.BookmarkWithItemInfo
import xyz.libravault.core.domain.model.Highlight
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.ReadingProgress
import xyz.libravault.core.domain.repository.BookmarkRepository
import xyz.libravault.core.domain.repository.HighlightRepository
import xyz.libravault.core.domain.repository.LibraryRepository
import xyz.libravault.core.domain.repository.ProgressRepository
import javax.inject.Inject

class GetLibraryItemUseCase @Inject constructor(
    private val libraryRepository: LibraryRepository,
) {
    suspend operator fun invoke(id: Long): LibraryItem? =
        libraryRepository.getItemById(id)
}

class GetReadingProgressUseCase @Inject constructor(
    private val progressRepository: ProgressRepository,
) {
    suspend operator fun invoke(itemId: Long): ReadingProgress? =
        progressRepository.getReadingProgress(itemId)
}

class ObserveBookmarksUseCase @Inject constructor(
    private val bookmarkRepository: BookmarkRepository,
) {
    operator fun invoke(itemId: Long): Flow<List<Bookmark>> =
        bookmarkRepository.observeBookmarks(itemId)
}

class AddBookmarkUseCase @Inject constructor(
    private val bookmarkRepository: BookmarkRepository,
) {
    suspend operator fun invoke(bookmark: Bookmark): Long =
        bookmarkRepository.addBookmark(bookmark)
}

class DeleteBookmarkUseCase @Inject constructor(
    private val bookmarkRepository: BookmarkRepository,
) {
    suspend operator fun invoke(id: Long) =
        bookmarkRepository.deleteBookmark(id)
}

class ObserveAllBookmarksUseCase @Inject constructor(
    private val bookmarkRepository: BookmarkRepository,
) {
    operator fun invoke(): Flow<List<BookmarkWithItemInfo>> =
        bookmarkRepository.observeAllBookmarksWithItem()
}

class ObserveHighlightsUseCase @Inject constructor(
    private val highlightRepository: HighlightRepository,
) {
    operator fun invoke(itemId: Long): Flow<List<Highlight>> =
        highlightRepository.observeHighlights(itemId)
}

class AddHighlightUseCase @Inject constructor(
    private val highlightRepository: HighlightRepository,
) {
    suspend operator fun invoke(highlight: Highlight): Long =
        highlightRepository.addHighlight(highlight)
}

class DeleteHighlightUseCase @Inject constructor(
    private val highlightRepository: HighlightRepository,
) {
    suspend operator fun invoke(id: Long) =
        highlightRepository.deleteHighlight(id)
}
