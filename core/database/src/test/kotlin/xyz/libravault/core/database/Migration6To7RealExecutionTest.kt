package xyz.libravault.core.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Executes MIGRATION_6_7 against a real, hand-built v6 SQLite database — not a mocked
 * `SupportSQLiteDatabase` with SQL strings asserted by eye, unlike MigrationsTest.kt's
 * existing MIGRATION_4_5 coverage. Worth the extra weight here specifically: MIGRATION_6_7
 * rebuilds `reading_progress` from scratch (SQLite can't reliably ALTER a column's type
 * or DROP a column across the SQLite versions real Android devices ship), and Room
 * validates the post-migration schema against its own codegen at runtime — a mismatch
 * anywhere (column order, type, nullability) throws `IllegalStateException` and crashes
 * the app for every user with a pre-v7 local database on their next open. That is not a
 * failure mode a string-content assertion on mocked SQL can catch.
 *
 * The v6 CREATE TABLE / index statements below are copied verbatim from Room's own
 * generated `LibravaultDatabase_Impl.kt` as it existed immediately before this migration
 * was added (captured 2026-08-13) — i.e. exactly what a real user's on-disk v6 database
 * looks like, not a hand-guessed approximation of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration6To7RealExecutionTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    /** Opens a raw (non-Room) SQLite connection at exactly the v6 schema, PRAGMA
     *  user_version included, so Room's own upgrade path (triggered in [runRealMigrationThroughRoom])
     *  has something real to migrate from. */
    private fun openHelperAtV6(name: String): SupportSQLiteOpenHelper {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val callback = object : SupportSQLiteOpenHelper.Callback(6) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `vault_folders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uri` TEXT NOT NULL, `displayName` TEXT NOT NULL, `addedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `library_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `vaultFolderId` INTEGER NOT NULL, `filePath` TEXT NOT NULL, `title` TEXT NOT NULL, `author` TEXT NOT NULL, `narrator` TEXT, `series` TEXT, `seriesIndex` REAL, `format` TEXT NOT NULL, `coverArtPath` TEXT, `durationMs` INTEGER, `pageCount` INTEGER, `addedAt` INTEGER NOT NULL, FOREIGN KEY(`vaultFolderId`) REFERENCES `vault_folders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_library_items_vaultFolderId` ON `library_items` (`vaultFolderId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_library_items_format` ON `library_items` (`format`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_library_items_title` ON `library_items` (`title`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_library_items_author` ON `library_items` (`author`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `reading_progress` (`itemId` INTEGER NOT NULL, `positionCfi` TEXT, `pageIndex` INTEGER, `markdownScrollOffset` INTEGER, `lastReadAt` INTEGER NOT NULL, PRIMARY KEY(`itemId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `listening_progress` (`itemId` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL, `chapterIndex` INTEGER NOT NULL, `lastListenedAt` INTEGER NOT NULL, `playbackSpeed` REAL NOT NULL, PRIMARY KEY(`itemId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `itemId` INTEGER NOT NULL, `positionRef` TEXT NOT NULL, `label` TEXT, `note` TEXT, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`itemId`) REFERENCES `library_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_itemId` ON `bookmarks` (`itemId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `highlights` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `itemId` INTEGER NOT NULL, `positionRef` TEXT NOT NULL, `highlightedText` TEXT NOT NULL, `colorHex` TEXT NOT NULL, `note` TEXT, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`itemId`) REFERENCES `library_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_highlights_itemId` ON `highlights` (`itemId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `collections` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `collection_items` (`collectionId` INTEGER NOT NULL, `itemId` INTEGER NOT NULL, PRIMARY KEY(`collectionId`, `itemId`), FOREIGN KEY(`collectionId`) REFERENCES `collections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`itemId`) REFERENCES `library_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_collection_items_collectionId` ON `collection_items` (`collectionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_collection_items_itemId` ON `collection_items` (`itemId`)")

                // Seed one library_items + reading_progress row with a real, non-null
                // pre-migration pixel offset, so the migration has something to prove it
                // drops rather than corrupts.
                db.execSQL(
                    "INSERT INTO `vault_folders` (`id`, `uri`, `displayName`, `addedAt`) VALUES (1, 'content://x', 'Vault', 1000)"
                )
                db.execSQL(
                    """
                    INSERT INTO `library_items`
                        (`id`, `vaultFolderId`, `filePath`, `title`, `author`, `format`, `addedAt`)
                    VALUES (1, 1, 'notes.md', 'Notes', 'Someone', 'MARKDOWN', 1000)
                    """.trimIndent()
                )
                db.execSQL(
                    "INSERT INTO `reading_progress` (`itemId`, `positionCfi`, `pageIndex`, `markdownScrollOffset`, `lastReadAt`) VALUES (1, NULL, NULL, 4200, 1700000000000)"
                )

                // A Markdown bookmark in the old pixel unit (id 1), an EPUB bookmark
                // (id 2, unprefixed CFI) and a PDF bookmark (id 3, "page:" prefix) —
                // only the first should be touched by the migration's UPDATE.
                db.execSQL(
                    "INSERT INTO `bookmarks` (`id`, `itemId`, `positionRef`, `label`, `note`, `createdAt`) VALUES (1, 1, 'scroll:4200', NULL, NULL, 1000)"
                )
                db.execSQL(
                    "INSERT INTO `bookmarks` (`id`, `itemId`, `positionRef`, `label`, `note`, `createdAt`) VALUES (2, 1, 'epubcfi(/6/4)', NULL, NULL, 1000)"
                )
                db.execSQL(
                    "INSERT INTO `bookmarks` (`id`, `itemId`, `positionRef`, `label`, `note`, `createdAt`) VALUES (3, 1, 'page:7', NULL, NULL, 1000)"
                )
            }

            override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        return factory.create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build())
    }

    @Test
    fun `MIGRATION_6_7 rebuilds reading_progress with markdownScrollFraction as REAL, dropping the old pixel value`() {
        val dbName = "migration_6_7_raw_test.db"
        context.deleteDatabase(dbName)
        val helper = openHelperAtV6(dbName)
        val db = helper.writableDatabase // triggers onCreate at v6, seeding the row above

        MIGRATION_6_7.migrate(db)

        // Schema shape: the new column is REAL, the old one is gone, nothing else moved.
        val columns = mutableListOf<Triple<String, String, Boolean>>() // name, type, notNull
        db.query("PRAGMA table_info(`reading_progress`)").use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            val typeIdx = cursor.getColumnIndex("type")
            val notNullIdx = cursor.getColumnIndex("notnull")
            while (cursor.moveToNext()) {
                columns += Triple(cursor.getString(nameIdx), cursor.getString(typeIdx), cursor.getInt(notNullIdx) == 1)
            }
        }
        assertEquals(
            listOf(
                Triple("itemId", "INTEGER", true),
                Triple("positionCfi", "TEXT", false),
                Triple("pageIndex", "INTEGER", false),
                Triple("markdownScrollFraction", "REAL", false),
                Triple("lastReadAt", "INTEGER", true),
            ),
            columns,
        )

        // Data: the old pixel value is gone (NULL), everything else survived untouched.
        db.query("SELECT `positionCfi`, `pageIndex`, `markdownScrollFraction`, `lastReadAt` FROM `reading_progress` WHERE `itemId` = 1").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertNull(cursor.getString(0)) // positionCfi
            assertNull(cursor.getString(1)) // pageIndex (isNull check via getString)
            assertNull(cursor.getString(2)) // markdownScrollFraction — the dropped value
            assertEquals(1_700_000_000_000L, cursor.getLong(3)) // lastReadAt preserved
        }

        db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `MIGRATION_6_7 resets Markdown bookmarks to scroll_0_0, leaving EPUB and PDF bookmarks untouched`() {
        val dbName = "migration_6_7_bookmarks_test.db"
        context.deleteDatabase(dbName)
        val db = openHelperAtV6(dbName).writableDatabase

        MIGRATION_6_7.migrate(db)

        val positionRefs = mutableMapOf<Long, String>()
        db.query("SELECT `id`, `positionRef` FROM `bookmarks` ORDER BY `id`").use { cursor ->
            val idIdx = cursor.getColumnIndex("id")
            val refIdx = cursor.getColumnIndex("positionRef")
            while (cursor.moveToNext()) {
                positionRefs[cursor.getLong(idIdx)] = cursor.getString(refIdx)
            }
        }

        // The whole point: without this reset, "scroll:4200" parses as a valid Double
        // (4200.0) under the new fraction interpretation instead of failing loudly, so
        // tapping this bookmark would have silently landed on the wrong section rather
        // than throwing — this proves it lands on a known, honest position instead.
        assertEquals("scroll:0.0", positionRefs[1])
        // Unrelated formats must survive completely untouched.
        assertEquals("epubcfi(/6/4)", positionRefs[2])
        assertEquals("page:7", positionRefs[3])

        db.close()
        context.deleteDatabase(dbName)
    }

    /**
     * The test that actually matters most: opens the same on-disk v6 database through
     * the real [LibravaultDatabase] Room class Room generates at v7, so Room's own
     * runtime schema validation (`TableInfo` comparison against ReadingProgressEntity's
     * codegen) has to accept MIGRATION_6_7's output — the exact check that throws
     * `IllegalStateException` and crashes a real app on a shape mismatch. If this test
     * passes, a v6 user upgrading past this change will not crash on next launch.
     */
    @Test
    fun `a real v6 database opens cleanly through Room at v7 and reads back through the DAO`() = runTest {
        val dbName = "migration_6_7_room_test.db"
        context.deleteDatabase(dbName)
        openHelperAtV6(dbName).writableDatabase.close() // seed + close, let Room reopen it

        val db = Room.databaseBuilder(context, LibravaultDatabase::class.java, dbName)
            .addMigrations(MIGRATION_6_7)
            .build()

        val progress = db.progressDao().getReadingProgress(1)

        assertEquals(1L, progress?.itemId)
        assertNull(progress?.markdownScrollFraction)

        db.close()
        context.deleteDatabase(dbName)
    }
}
