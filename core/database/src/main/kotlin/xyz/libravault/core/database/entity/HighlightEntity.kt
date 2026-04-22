package xyz.libravault.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "highlights",
    foreignKeys = [ForeignKey(
        entity = LibraryItemEntity::class,
        parentColumns = ["id"],
        childColumns = ["itemId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("itemId")],
)
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val positionRef: String,        // CFI range for EPUB, "page:N:x1,y1,x2,y2" for PDF
    val highlightedText: String,
    val colorHex: String = "#FFE066", // Default amber highlight
    val note: String? = null,
    val createdAt: Long,
)
