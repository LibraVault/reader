package xyz.libravault.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from v1→v2: adds the highlights table.
 *
 * v1 schema (initial release — M0/M1):
 *  - vault_folders, library_items, reading_progress, listening_progress, bookmarks
 *
 * v2 schema (M2 — reader with highlights):
 *  - adds `highlights` table with FK to library_items (CASCADE on delete)
 *  - index on itemId
 */
val MIGRATION_1_2 = Migration(1, 2) { db ->
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `highlights` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `itemId` INTEGER NOT NULL,
            `positionRef` TEXT NOT NULL,
            `highlightedText` TEXT NOT NULL,
            `colorHex` TEXT NOT NULL DEFAULT '#FFE066',
            `note` TEXT,
            `createdAt` INTEGER NOT NULL,
            FOREIGN KEY (`itemId`) REFERENCES `library_items`(`id`) ON DELETE CASCADE
        )
        """.trimIndent()
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_highlights_itemId` ON `highlights`(`itemId`)")
}

/**
 * Migration from v2→v3: adds collections + collection_items tables.
 *
 * v3 schema (M1 — collections/shelves):
 *  - adds `collections` table
 *  - adds `collection_items` junction table with FK to collections and library_items
 */
val MIGRATION_2_3 = Migration(2, 3) { db ->
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `collections` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `name` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL
        )
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `collection_items` (
            `collectionId` INTEGER NOT NULL,
            `itemId` INTEGER NOT NULL,
            PRIMARY KEY(`collectionId`, `itemId`),
            FOREIGN KEY (`collectionId`) REFERENCES `collections`(`id`) ON DELETE CASCADE,
            FOREIGN KEY (`itemId`) REFERENCES `library_items`(`id`) ON DELETE CASCADE
        )
        """.trimIndent()
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_collection_items_collectionId` ON `collection_items`(`collectionId`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_collection_items_itemId` ON `collection_items`(`itemId`)")
}

/**
 * Migration from v3→v4: adds playbackSpeed to listening_progress.
 *
 * v4 schema (LIB-326 — per-book speed persistence):
 *  - adds `playbackSpeed` REAL column to `listening_progress` (default 1.0)
 */
val MIGRATION_3_4 = Migration(3, 4) { db ->
    db.execSQL(
        "ALTER TABLE `listening_progress` ADD COLUMN `playbackSpeed` REAL NOT NULL DEFAULT 1.0"
    )
}

/**
 * Migration from v4→v5: adds note field to bookmarks.
 *
 * v5 schema:
 *  - adds nullable `note` TEXT column to `bookmarks`
 *
 * Idempotent: if a previous working-tree build deployed the updated BookmarkEntity
 * (with `note`) at schema version 4, Room's fallbackToDestructiveMigration would
 * have left the column already present. The PRAGMA check avoids the SQLiteException
 * "duplicate column name: note" that would otherwise cause a persistent crash loop.
 */
val MIGRATION_4_5 = Migration(4, 5) { db ->
    val cursor = db.query("PRAGMA table_info(`bookmarks`)")
    val alreadyHasNote = cursor.use { c ->
        val nameIdx = c.getColumnIndex("name")
        var found = false
        while (c.moveToNext()) {
            if (c.getString(nameIdx) == "note") { found = true; break }
        }
        found
    }
    if (!alreadyHasNote) {
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `note` TEXT")
    }
}

/**
 * Migration from v5→v6: adds markdownScrollOffset to reading_progress.
 *
 * v6 schema (Markdown viewer):
 *  - adds nullable `markdownScrollOffset` INTEGER column to `reading_progress`,
 *    storing the scroll position (px) for Markdown documents — same table used
 *    by EPUB's `positionCfi` and PDF's `pageIndex`.
 */
val MIGRATION_5_6 = Migration(5, 6) { db ->
    db.execSQL(
        "ALTER TABLE `reading_progress` ADD COLUMN `markdownScrollOffset` INTEGER"
    )
}

/**
 * Migration from v6→v7: replaces markdownScrollOffset (Int, pixels) with
 * markdownScrollFraction (Double, 0.0..1.0) on reading_progress.
 *
 * v6's pixel offset was fragile — it's only meaningful against the exact layout
 * that produced it, so changing font size, reading theme, or device rotation
 * between sessions reflows the document to a different total height and the
 * restored offset lands somewhere unrelated to where the reader actually stopped
 * (worse the further into a long document). iOS's Markdown reader already stored a
 * fraction for exactly this reason (see #125) — this brings Android in line.
 *
 * Old pixel values are dropped, not converted: a pixel offset alone can't be turned
 * into a fraction without the total scrollable height it was measured against, and
 * that was never persisted. The rows affected are a narrow set in practice (only
 * users with an in-progress Markdown read at the moment they upgrade past this
 * migration), and per the paragraph above, the value being dropped was already an
 * unreliable position — restarting at the top of the document over silently landing
 * on a wrong-looking-but-plausible one is the safer failure mode.
 *
 * SQLite's ALTER TABLE can't change a column's type or drop a column reliably across
 * the range of SQLite versions Android devices in the wild actually ship (DROP COLUMN
 * needs SQLite >= 3.35.0, inconsistent in practice) — so this uses the standard
 * Room-recommended rebuild-the-table pattern instead: create the new shape, copy over
 * every column except the one being dropped, delete the old table, rename the new one
 * into place. Column order/types/nullability mirror exactly what Room's own codegen
 * expects for ReadingProgressEntity at this version — see
 * LibravaultDatabase_Impl.kt's `CREATE TABLE reading_progress` statement, which Room
 * validates the post-migration schema against at runtime and throws on any mismatch.
 *
 * Also resets `bookmarks.positionRef` for Markdown bookmarks in the same migration —
 * they encode position as `"scroll:<value>"` strings (see ReaderScreen.kt), entirely
 * independent of this table, but in the same fragile pixel unit. No schema change is
 * needed there (`positionRef` stays a plain TEXT column), but leaving old rows alone
 * would be a real, silent correctness bug, not just a missed cleanup: `"scroll:4200"`
 * parses just fine as `4200.0` under the new fraction interpretation — nothing throws —
 * it just silently coerces to the last section (or wherever `4200 * sectionCount`
 * happens to land), taking the user to a wrong-but-plausible-looking place on tap
 * rather than where they actually left the bookmark. Reset to `"scroll:0.0"` rather
 * than deleted: the same "unreliable value → restart at a known-honest position, not a
 * wrong-looking precise one" reasoning as reading_progress above, but keeping the
 * bookmark itself intact (it's a deliberate user action, unlike auto-saved progress)
 * so the user can see it, notice, and re-set it rather than losing it outright. The
 * `LIKE 'scroll:%'` prefix alone disambiguates Markdown bookmarks from EPUB (raw
 * CFI/locator JSON, no prefix) and PDF (`"page:<n>"`) — no join to library_items or its
 * format column needed.
 */
val MIGRATION_6_7 = Migration(6, 7) { db ->
    db.execSQL(
        """
        CREATE TABLE `reading_progress_new` (
            `itemId` INTEGER NOT NULL,
            `positionCfi` TEXT,
            `pageIndex` INTEGER,
            `markdownScrollFraction` REAL,
            `lastReadAt` INTEGER NOT NULL,
            PRIMARY KEY(`itemId`)
        )
        """.trimIndent()
    )
    db.execSQL(
        """
        INSERT INTO `reading_progress_new` (`itemId`, `positionCfi`, `pageIndex`, `markdownScrollFraction`, `lastReadAt`)
        SELECT `itemId`, `positionCfi`, `pageIndex`, NULL, `lastReadAt` FROM `reading_progress`
        """.trimIndent()
    )
    db.execSQL("DROP TABLE `reading_progress`")
    db.execSQL("ALTER TABLE `reading_progress_new` RENAME TO `reading_progress`")

    db.execSQL("UPDATE `bookmarks` SET `positionRef` = 'scroll:0.0' WHERE `positionRef` LIKE 'scroll:%'")
}
