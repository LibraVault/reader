package xyz.libravault.core.database

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MigrationsTest {

    // ── MIGRATION_4_5: idempotent bookmark note column add ────────────────────

    @Test
    fun `MIGRATION_4_5 adds note column when it does not exist`() {
        val mockDb = mockk<SupportSQLiteDatabase>()
        val mockCursor = mockk<Cursor>()

        // Mock PRAGMA query: no "note" column present
        every { mockDb.query("PRAGMA table_info(`bookmarks`)") } returns mockCursor
        every { mockCursor.getColumnIndex("name") } returns 0
        every { mockCursor.moveToNext() } returns false // No rows returned = no columns
        every { mockCursor.close() } returns Unit

        val sqlSlot = slot<String>()
        every { mockDb.execSQL(capture(sqlSlot)) } returns Unit

        MIGRATION_4_5.migrate(mockDb)

        verify { mockDb.execSQL(any()) }
        val executedSql = sqlSlot.captured
        assertTrue(executedSql.contains("ALTER TABLE `bookmarks` ADD COLUMN `note` TEXT"))
    }

    @Test
    fun `MIGRATION_4_5 does not add note column when it already exists`() {
        val mockDb = mockk<SupportSQLiteDatabase>()
        val mockCursor = mockk<Cursor>()

        // Mock PRAGMA query: "note" column already present
        every { mockDb.query("PRAGMA table_info(`bookmarks`)") } returns mockCursor
        every { mockCursor.getColumnIndex("name") } returns 0
        every { mockCursor.moveToNext() } returnsMany listOf(true, false) // One row with "note"
        every { mockCursor.getString(0) } returns "note"
        every { mockCursor.close() } returns Unit

        var sqlExecuted = false
        every { mockDb.execSQL(any()) } answers { sqlExecuted = true }

        MIGRATION_4_5.migrate(mockDb)

        assertFalse(sqlExecuted, "ALTER TABLE should not be executed when column already exists")
    }

    @Test
    fun `MIGRATION_4_5 skips other columns in PRAGMA result`() {
        val mockDb = mockk<SupportSQLiteDatabase>()
        val mockCursor = mockk<Cursor>()

        // Mock PRAGMA: returns rows for "id", "itemId", ..., then "note"
        every { mockDb.query("PRAGMA table_info(`bookmarks`)") } returns mockCursor
        every { mockCursor.getColumnIndex("name") } returns 0

        // Simulate iterating through multiple columns: id, itemId, position, ..., note
        every { mockCursor.moveToNext() } returnsMany listOf(
            true,  // id
            true,  // itemId
            true,  // positionInItem
            true,  // createdAt
            false  // end
        )
        every { mockCursor.getString(0) } returnsMany listOf("id", "itemId", "positionInItem", "createdAt")
        every { mockCursor.close() } returns Unit

        var sqlExecuted = false
        every { mockDb.execSQL(any()) } answers { sqlExecuted = true }

        MIGRATION_4_5.migrate(mockDb)

        assertTrue(sqlExecuted, "ALTER TABLE should be executed when 'note' not found in PRAGMA result")
    }
}
