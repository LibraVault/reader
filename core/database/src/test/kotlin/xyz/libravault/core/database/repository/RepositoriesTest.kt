package xyz.libravault.core.database.repository

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import xyz.libravault.core.database.dao.BookmarkDao
import xyz.libravault.core.database.dao.CollectionDao
import xyz.libravault.core.database.dao.HighlightDao
import xyz.libravault.core.database.dao.LibraryItemDao
import xyz.libravault.core.database.dao.ProgressDao
import xyz.libravault.core.database.dao.VaultFolderDao
import xyz.libravault.core.database.entity.BookmarkEntity
import xyz.libravault.core.database.entity.BookmarkWithItem
import xyz.libravault.core.database.entity.CollectionEntity
import xyz.libravault.core.database.entity.HighlightEntity
import xyz.libravault.core.database.entity.LibraryItemEntity
import xyz.libravault.core.database.entity.ListeningProgressEntity
import xyz.libravault.core.database.entity.ReadingProgressEntity
import xyz.libravault.core.database.entity.VaultFolderEntity
import xyz.libravault.core.domain.model.Bookmark
import xyz.libravault.core.domain.model.Highlight
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.ListeningProgress
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.core.domain.model.ReadingProgress
import java.time.Instant

/**
 * The Entity<->domain mapper functions in Repositories.kt (toDomain()/toEntity(),
 * ~10 of them) are file-private, so they're exercised here through each public
 * Repository method — mocking the DAO and asserting on the mapped domain object.
 * Focused on the mappings with real logic (epoch-millis<->Instant conversion,
 * MediaFormat's fallback-on-corrupt-string behavior), not every 1:1 field copy.
 */
class VaultRepositoryImplTest {
    private val dao = mockk<VaultFolderDao>()
    private val repository = VaultRepositoryImpl(dao)

    @Test
    fun `observeVaults maps epoch millis to Instant`() = runTest {
        val entity = VaultFolderEntity(id = 1, uri = "content://vault", displayName = "Books", addedAt = 1_700_000_000_000)
        every { dao.observeAll() } returns flowOf(listOf(entity))

        val result = repository.observeVaults().let { flow ->
            var value: List<xyz.libravault.core.domain.model.VaultFolder>? = null
            flow.collect { value = it }
            value
        }

        val vault = result?.single()
        assertEquals(1L, vault?.id)
        assertEquals("content://vault", vault?.uri)
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000), vault?.addedAt)
    }

    @Test
    fun `addVault assigns the DAO-generated id to the returned domain object`() = runTest {
        coEvery { dao.insert(any()) } returns 99L

        val result = repository.addVault(uri = "content://x", displayName = "X")

        assertEquals(99L, result.id)
        assertEquals("X", result.displayName)
    }
}

class LibraryRepositoryImplTest {
    private val dao = mockk<LibraryItemDao>()
    private val repository = LibraryRepositoryImpl(dao)

    private fun entity(format: String) = LibraryItemEntity(
        id = 1, vaultFolderId = 1, filePath = "x", title = "T", author = "A",
        narrator = null, series = null, seriesIndex = null,
        format = format, coverArtPath = null, durationMs = null, pageCount = null,
        addedAt = 1_700_000_000_000,
    )

    @Test
    fun `getItemById maps a valid format string to its MediaFormat`() = runTest {
        coEvery { dao.getItemById(1) } returns entity(format = "M4B")

        val item = repository.getItemById(1)

        assertEquals(MediaFormat.M4B, item?.format)
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000), item?.addedAt)
    }

    @Test
    fun `getItemById falls back to EPUB for a corrupt or unrecognized format string`() = runTest {
        // format is stored as a raw string (MediaFormat.name()) — if the enum is ever
        // renamed/removed, or the column is corrupted, valueOf() throws. toDomain()
        // catches that and falls back rather than crashing the whole list.
        coEvery { dao.getItemById(1) } returns entity(format = "NOT_A_REAL_FORMAT")

        val item = repository.getItemById(1)

        assertEquals(MediaFormat.EPUB, item?.format)
    }

    @Test
    fun `upsert converts the domain item to an entity with format name() and epoch millis`() = runTest {
        val slot = io.mockk.slot<LibraryItemEntity>()
        coEvery { dao.upsert(capture(slot)) } returns 5L
        val item = LibraryItem(
            id = 0, vaultFolderId = 1, filePath = "x", title = "T", author = "A",
            format = MediaFormat.PDF, addedAt = Instant.ofEpochMilli(1_700_000_000_000),
        )

        repository.upsert(item)

        assertEquals("PDF", slot.captured.format)
        assertEquals(1_700_000_000_000, slot.captured.addedAt)
    }
}

class ProgressRepositoryImplTest {
    private val dao = mockk<ProgressDao>()
    private val repository = ProgressRepositoryImpl(dao)

    @Test
    fun `getReadingProgress maps epoch millis to Instant`() = runTest {
        coEvery { dao.getReadingProgress(1) } returns ReadingProgressEntity(
            itemId = 1, positionCfi = "epubcfi(/6/4)", pageIndex = null, markdownScrollOffset = null, lastReadAt = 1_700_000_000_000,
        )

        val progress = repository.getReadingProgress(1)

        assertEquals("epubcfi(/6/4)", progress?.positionCfi)
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000), progress?.lastReadAt)
    }

    @Test
    fun `saveReadingProgress converts Instant back to epoch millis`() = runTest {
        val slot = io.mockk.slot<ReadingProgressEntity>()
        coEvery { dao.upsertReadingProgress(capture(slot)) } returns Unit

        repository.saveReadingProgress(
            ReadingProgress(itemId = 1, positionCfi = null, pageIndex = 3, lastReadAt = Instant.ofEpochMilli(42_000))
        )

        assertEquals(42_000L, slot.captured.lastReadAt)
    }

    @Test
    fun `getListeningProgress maps epoch millis to Instant`() = runTest {
        coEvery { dao.getListeningProgress(1) } returns ListeningProgressEntity(
            itemId = 1, positionMs = 5_000, chapterIndex = 2, lastListenedAt = 1_700_000_000_000, playbackSpeed = 1.5f,
        )

        val progress = repository.getListeningProgress(1)

        assertEquals(2, progress?.chapterIndex)
        assertEquals(1.5f, progress?.playbackSpeed)
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000), progress?.lastListenedAt)
    }

    @Test
    fun `saveListeningProgress converts Instant back to epoch millis`() = runTest {
        val slot = io.mockk.slot<ListeningProgressEntity>()
        coEvery { dao.upsertListeningProgress(capture(slot)) } returns Unit

        repository.saveListeningProgress(
            ListeningProgress(itemId = 1, positionMs = 9_000, chapterIndex = 4, lastListenedAt = Instant.ofEpochMilli(88_000), playbackSpeed = 2.0f)
        )

        assertEquals(88_000L, slot.captured.lastListenedAt)
        assertEquals(4, slot.captured.chapterIndex)
    }
}

class BookmarkRepositoryImplTest {
    private val dao = mockk<BookmarkDao>()
    private val repository = BookmarkRepositoryImpl(dao)

    @Test
    fun `observeBookmarks maps epoch millis to Instant`() = runTest {
        every { dao.observeBookmarks(1) } returns flowOf(listOf(
            BookmarkEntity(id = 1, itemId = 1, positionRef = "p1", label = "L", note = null, createdAt = 1_700_000_000_000)
        ))

        val bookmarks = mutableListOf<List<Bookmark>>()
        repository.observeBookmarks(1).collect { bookmarks.add(it) }

        assertEquals(Instant.ofEpochMilli(1_700_000_000_000), bookmarks.single().single().createdAt)
    }

    @Test
    fun `observeAllBookmarksWithItem maps the joined item fields and falls back on a corrupt format`() = runTest {
        every { dao.observeAllBookmarks() } returns flowOf(listOf(
            BookmarkWithItem(
                id = 1, itemId = 1, positionRef = "p1", label = null, note = null, createdAt = 1_700_000_000_000,
                itemTitle = "Title", itemAuthor = "Author", itemFormat = "garbage",
            )
        ))

        val results = mutableListOf<List<xyz.libravault.core.domain.model.BookmarkWithItemInfo>>()
        repository.observeAllBookmarksWithItem().collect { results.add(it) }

        val info = results.single().single()
        assertEquals("Title", info.itemTitle)
        assertEquals("Author", info.itemAuthor)
        assertEquals(MediaFormat.EPUB, info.itemFormat) // "garbage" falls back, same as LibraryItemEntity.toDomain
    }

    @Test
    fun `addBookmark converts Instant back to epoch millis`() = runTest {
        val slot = io.mockk.slot<BookmarkEntity>()
        coEvery { dao.insert(capture(slot)) } returns 1L

        repository.addBookmark(Bookmark(id = 0, itemId = 1, positionRef = "p1", label = null, note = null, createdAt = Instant.ofEpochMilli(7_000)))

        assertEquals(7_000L, slot.captured.createdAt)
    }
}

class HighlightRepositoryImplTest {
    private val dao = mockk<HighlightDao>()
    private val repository = HighlightRepositoryImpl(dao)

    @Test
    fun `observeHighlights maps epoch millis to Instant`() = runTest {
        every { dao.observeHighlights(1) } returns flowOf(listOf(
            HighlightEntity(id = 1, itemId = 1, positionRef = "p1", highlightedText = "quote", createdAt = 1_700_000_000_000)
        ))

        val highlights = mutableListOf<List<Highlight>>()
        repository.observeHighlights(1).collect { highlights.add(it) }

        assertEquals("quote", highlights.single().single().highlightedText)
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000), highlights.single().single().createdAt)
    }
}

class CollectionRepositoryImplTest {
    private val dao = mockk<CollectionDao>()
    private val repository = CollectionRepositoryImpl(dao)

    @Test
    fun `getById maps epoch millis to Instant without an unused item-ids lookup`() = runTest {
        coEvery { dao.getById(1) } returns CollectionEntity(id = 1, name = "Favorites", createdAt = 1_700_000_000_000, updatedAt = 1_800_000_000_000)

        val collection = repository.getById(1)

        assertEquals("Favorites", collection?.name)
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000), collection?.createdAt)
        assertEquals(Instant.ofEpochMilli(1_800_000_000_000), collection?.updatedAt)
        // Regression guard: toDomain() used to take the DAO and call getItemIds()
        // just to discard the result (Collection has no itemIds field) — a wasted
        // query on every collection load. Confirm that's gone.
        io.mockk.coVerify(exactly = 0) { dao.getItemIds(any()) }
    }

    @Test
    fun `getById returns null when the collection does not exist`() = runTest {
        coEvery { dao.getById(404) } returns null

        assertNull(repository.getById(404))
    }
}
