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
