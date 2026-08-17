package xyz.libravault.core.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Runs the **entire** migration chain, v1 → v7, against real SQLite.
 *
 * Six migrations exist. Before this, two were tested: `MIGRATION_4_5` against a
 * *mocked* `SupportSQLiteDatabase` asserting SQL substrings (which verifies the
 * string looks right, not that SQLite accepts it), and `MIGRATION_6_7` for real
 * (`Migration6To7RealExecutionTest`). Migrations 1→2, 2→3, 3→4 and 5→6 had no
 * execution coverage at all — see docs/TEST_COVERAGE_PRD.md, S2.
 *
 * A chain test rather than four isolated ones, deliberately. The failure this
 * guards against is a user on an old build opening the app after a long gap:
 * Room applies every migration in sequence and then validates the result
 * against its own codegen. A mismatch anywhere — column order, type,
 * nullability, a missing index — throws `IllegalStateException` on their device.
 * Testing migrations individually cannot catch an interaction between them, and
 * the sequence is the thing users actually execute.
 *
 * ## Where the v1 schema comes from
 *
 * Room never exported schemas for v1..v6 (`room.schemaLocation` was unset until
 * this PR), so there is no historical JSON to load and `MigrationTestHelper`
 * cannot construct those versions.
 *
 * The v1 DDL below is *derived*, and the derivation is sound rather than
 * guessed: migrations are by definition the complete record of schema change
 * between versions, so v1 = the known-verbatim v6 schema (captured from Room's
 * generated `LibravaultDatabase_Impl.kt` in `Migration6To7RealExecutionTest`)
 * minus exactly what migrations 1→6 add:
 *
 * | Migration | Adds |
 * |---|---|
 * | 1→2 | `highlights` table + index |
 * | 2→3 | `collections`, `collection_items` + indices |
 * | 3→4 | `listening_progress.playbackSpeed` |
 * | 4→5 | `bookmarks.note` |
 * | 5→6 | `reading_progress.markdownScrollOffset` |
 *
 * `vault_folders` and `library_items` are untouched across 1→6, so they appear
 * here exactly as in the v6 capture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationChainTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun openHelperAtV1(name: String): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `vault_folders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`uri` TEXT NOT NULL, `displayName` TEXT NOT NULL, `addedAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `library_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`vaultFolderId` INTEGER NOT NULL, `filePath` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`author` TEXT NOT NULL, `narrator` TEXT, `series` TEXT, `seriesIndex` REAL, " +
                        "`format` TEXT NOT NULL, `coverArtPath` TEXT, `durationMs` INTEGER, `pageCount` INTEGER, " +
                        "`addedAt` INTEGER NOT NULL, FOREIGN KEY(`vaultFolderId`) REFERENCES `vault_folders`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_library_items_vaultFolderId` ON `library_items` (`vaultFolderId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_library_items_format` ON `library_items` (`format`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_library_items_title` ON `library_items` (`title`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_library_items_author` ON `library_items` (`author`)")
                // No markdownScrollOffset (added 5→6).
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reading_progress` (`itemId` INTEGER NOT NULL, `positionCfi` TEXT, " +
                        "`pageIndex` INTEGER, `lastReadAt` INTEGER NOT NULL, PRIMARY KEY(`itemId`))",
                )
                // No playbackSpeed (added 3→4).
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `listening_progress` (`itemId` INTEGER NOT NULL, " +
                        "`positionMs` INTEGER NOT NULL, `chapterIndex` INTEGER NOT NULL, " +
                        "`lastListenedAt` INTEGER NOT NULL, PRIMARY KEY(`itemId`))",
                )
                // No note (added 4→5).
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`itemId` INTEGER NOT NULL, `positionRef` TEXT NOT NULL, `label` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, FOREIGN KEY(`itemId`) REFERENCES `library_items`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_itemId` ON `bookmarks` (`itemId`)")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name).callback(callback).build(),
        )
    }

    /** Seeds one row in every v1 table, so the chain has real data to preserve. */
    private fun seedV1Data(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO vault_folders (id, uri, displayName, addedAt) VALUES (1, 'content://v1', 'Old Vault', 1000)")
        db.execSQL(
            "INSERT INTO library_items (id, vaultFolderId, filePath, title, author, format, addedAt) " +
                "VALUES (1, 1, '/books/old.epub', 'Pre-existing Book', 'Author A', 'EPUB', 2000)",
        )
        db.execSQL("INSERT INTO reading_progress (itemId, positionCfi, pageIndex, lastReadAt) VALUES (1, 'epubcfi(/6/4)', 7, 3000)")
        db.execSQL("INSERT INTO listening_progress (itemId, positionMs, chapterIndex, lastListenedAt) VALUES (1, 45000, 2, 4000)")
        db.execSQL("INSERT INTO bookmarks (id, itemId, positionRef, label, createdAt) VALUES (1, 1, 'epubcfi(/6/4)', 'Chapter one', 5000)")
    }

    private fun openThroughRoomAtV7(name: String): LibravaultDatabase =
        Room.databaseBuilder(context, LibravaultDatabase::class.java, name)
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
            )
            .build()

    /**
     * The headline test: a v1 database opened by today's build must migrate all
     * the way to v7 and pass Room's own schema validation.
     *
     * Room only validates the final schema when a query actually runs, so this
     * performs a real read rather than just opening the database — an assertion
     * that merely built the database would pass even against a broken schema.
     */
    @Test
    fun `a v1 database migrates cleanly through every version to v7`() = runTest {
        val name = "chain-v1-to-v7.db"
        context.getDatabasePath(name).delete()

        openHelperAtV1(name).use { helper ->
            seedV1Data(helper.writableDatabase)
        }

        val db = openThroughRoomAtV7(name)
        try {
            // Forces Room to open, run all six migrations, and validate the
            // resulting schema against its generated identity hash.
            val items = db.libraryItemDao().observeAll().first()
            assertEquals("v1 data must survive the full chain", 1, items.size)
            assertEquals("Pre-existing Book", items.first().title)
        } finally {
            db.close()
            context.getDatabasePath(name).delete()
        }
    }

    /**
     * Data preservation, per migration that touches an existing table.
     *
     * 3→4, 4→5 and 5→6 are `ALTER TABLE ... ADD COLUMN`, and 6→7 rebuilds
     * `reading_progress` outright. A rebuild that forgot to copy rows across
     * would still leave a structurally valid database and still pass a
     * schema-only assertion — it would just silently lose every user's reading
     * position. So this asserts the *values*, not just the columns.
     */
    @Test
    fun `existing rows survive the chain with correct values and new-column defaults`() = runTest {
        val name = "chain-data-preservation.db"
        context.getDatabasePath(name).delete()

        openHelperAtV1(name).use { helper -> seedV1Data(helper.writableDatabase) }

        val db = openThroughRoomAtV7(name)
        try {
            val raw = db.openHelper.readableDatabase

            // markdownScrollOffset is transient: 5→6 adds it (INTEGER, pixels)
            // and 6→7 replaces it with markdownScrollFraction (REAL), setting
            // it to NULL for every pre-existing row — a pixel offset cannot be
            // converted to a fraction without knowing the document height. So
            // the column to assert at v7 is the fraction, and NULL here is the
            // migration behaving correctly, not data being lost by accident.
            raw.query("SELECT positionCfi, pageIndex, markdownScrollFraction FROM reading_progress WHERE itemId = 1").use { c ->
                assertTrue("reading_progress row was lost during the 6→7 rebuild", c.moveToFirst())
                assertEquals("epubcfi(/6/4)", c.getString(0))
                assertEquals(7, c.getInt(1))
                assertTrue("markdownScrollFraction is intentionally NULL for rows migrated from v6", c.isNull(2))
            }

            raw.query("SELECT positionMs, playbackSpeed FROM listening_progress WHERE itemId = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(45000, c.getLong(0))
                assertEquals("playbackSpeed added by 3→4 defaults to 1.0", 1.0f, c.getFloat(1), 0.0001f)
            }

            raw.query("SELECT label, note FROM bookmarks WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Chapter one", c.getString(0))
                assertTrue("note added by 4→5 must default to NULL", c.isNull(1))
            }
        } finally {
            db.close()
            context.getDatabasePath(name).delete()
        }
    }

    /**
     * Tables introduced mid-chain must exist and be usable at v7, not merely
     * declared. `highlights` arrives at 1→2 and `collections`/`collection_items`
     * at 2→3, so a v1 user reaches them only through the chain.
     */
    @Test
    fun `tables added mid-chain exist and accept writes at v7`() = runTest {
        val name = "chain-added-tables.db"
        context.getDatabasePath(name).delete()

        openHelperAtV1(name).use { helper -> seedV1Data(helper.writableDatabase) }

        val db = openThroughRoomAtV7(name)
        try {
            val raw = db.openHelper.writableDatabase
            raw.execSQL(
                "INSERT INTO highlights (itemId, positionRef, highlightedText, colorHex, createdAt) " +
                    "VALUES (1, 'epubcfi(/6/4)', 'a quoted line', '#FFE066', 6000)",
            )
            raw.execSQL("INSERT INTO collections (id, name, createdAt, updatedAt) VALUES (1, 'Favourites', 7000, 7000)")
            raw.execSQL("INSERT INTO collection_items (collectionId, itemId) VALUES (1, 1)")

            raw.query("SELECT highlightedText FROM highlights WHERE itemId = 1").use { c ->
                assertTrue("highlights table (added 1→2) unusable at v7", c.moveToFirst())
                assertEquals("a quoted line", c.getString(0))
            }
            raw.query("SELECT c.name FROM collections c JOIN collection_items ci ON ci.collectionId = c.id WHERE ci.itemId = 1").use { c ->
                assertTrue("collections join (added 2→3) unusable at v7", c.moveToFirst())
                assertEquals("Favourites", c.getString(0))
            }
        } finally {
            db.close()
            context.getDatabasePath(name).delete()
        }
    }

    /**
     * The CASCADE deletes declared on tables added mid-chain must actually be
     * in force after migrating, not just present in the DDL text. Foreign keys
     * are off by default in SQLite and Room enables them per-connection, so a
     * migration that recreated a table without its FK clause would leave
     * orphaned rows behind on delete.
     */
    @Test
    fun `foreign key cascade still works for tables added mid-chain`() = runTest {
        val name = "chain-cascade.db"
        context.getDatabasePath(name).delete()

        openHelperAtV1(name).use { helper -> seedV1Data(helper.writableDatabase) }

        val db = openThroughRoomAtV7(name)
        try {
            val raw = db.openHelper.writableDatabase
            raw.execSQL(
                "INSERT INTO highlights (itemId, positionRef, highlightedText, colorHex, createdAt) " +
                    "VALUES (1, 'ref', 'text', '#FFE066', 6000)",
            )
            raw.execSQL("DELETE FROM library_items WHERE id = 1")

            raw.query("SELECT COUNT(*) FROM highlights").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("deleting a library item must cascade to its highlights", 0, c.getInt(0))
            }
        } finally {
            db.close()
            context.getDatabasePath(name).delete()
        }
    }

    /** The exported schema JSON must exist and be the version this build declares. */
    @Test
    fun `room exports the current schema so future migrations can be tested properly`() {
        val schema = java.io.File("schemas/xyz.libravault.core.database.LibravaultDatabase/7.json")
        assertTrue(
            "Expected an exported Room schema at ${schema.absolutePath}. " +
                "exportSchema=true is meaningless without room.schemaLocation in build.gradle.kts.",
            schema.isFile,
        )
        assertNotNull(schema.readText())
        assertTrue("exported schema should declare version 7", schema.readText().contains("\"version\": 7"))
    }
}
