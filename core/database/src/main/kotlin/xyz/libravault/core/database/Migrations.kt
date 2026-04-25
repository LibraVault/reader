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
