package xyz.libravault.feature.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import xyz.libravault.core.vaultstore.VaultBookmark

/** Sort key / label formatting for [VaultBookmark.positionRef] — only the two
 * conventions the vault reader actually produces (`"page:N"` for PDF, a
 * Readium Locator JSON blob for EPUB). A private duplicate of
 * `feature:reader`'s `ReaderComponents.kt` equivalents rather than a new
 * cross-module dependency, matching how the rest of `feature:vault`'s
 * reading UI is intentionally parallel, not shared. */
private fun vaultPositionRefSortKey(ref: String): Long = when {
    ref.startsWith("page:") -> (ref.removePrefix("page:").toIntOrNull() ?: Int.MAX_VALUE).toLong()
    ref.startsWith("{") -> runCatching {
        val locs = JSONObject(ref).optJSONObject("locations")
        val prog = locs?.optDouble("totalProgression", -1.0)?.takeIf { it >= 0 }
        val pos  = locs?.optInt("position", -1)?.takeIf { it >= 0 }
        when {
            prog != null -> (prog * 1_000_000).toLong()
            pos  != null -> pos.toLong()
            else         -> Long.MAX_VALUE
        }
    }.getOrDefault(Long.MAX_VALUE)
    else -> Long.MAX_VALUE
}

private fun formatVaultBookmarkLabel(positionRef: String): String = when {
    positionRef.startsWith("page:") ->
        positionRef.removePrefix("page:").toIntOrNull()?.let { "Page ${it + 1}" } ?: positionRef
    positionRef.startsWith("{") -> runCatching {
        val json = JSONObject(positionRef)
        val title = json.optString("title").takeIf { it.isNotBlank() }
        val locs  = json.optJSONObject("locations")
        val pos   = locs?.optInt("position", -1)?.takeIf { it >= 0 }
        val prog  = locs?.optDouble("totalProgression", -1.0)?.takeIf { it >= 0 }
        when {
            title != null && pos  != null -> "$title, p.$pos"
            title != null && prog != null -> "$title (${(prog * 100).toInt()}%)"
            title != null                 -> title
            pos   != null                 -> "Position $pos"
            prog  != null                 -> "${(prog * 100).toInt()}% through"
            else                          -> "Bookmark"
        }
    }.getOrDefault("Bookmark")
    else -> positionRef
}

/** Bookmarks sheet for vault content — same swipe-to-delete + edit-note-dialog
 * shape as `feature:reader`'s `BookmarksSheet`, over [VaultBookmark] instead
 * of `core.domain.model.Bookmark`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultBookmarksSheet(
    bookmarks: List<VaultBookmark>,
    onBookmarkClick: (VaultBookmark) -> Unit,
    onBookmarkDelete: (Long) -> Unit,
    onEditNote: (Long, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sorted = remember(bookmarks) { bookmarks.sortedBy { vaultPositionRefSortKey(it.positionRef) } }
    var editingBookmark by remember { mutableStateOf<VaultBookmark?>(null) }
    var noteText by remember { mutableStateOf("") }

    editingBookmark?.let { bm ->
        AlertDialog(
            onDismissRequest = { editingBookmark = null },
            title = { Text("Edit note") },
            text = {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Note") },
                    singleLine = false,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(onClick = {
                    onEditNote(bm.id, noteText.takeIf { it.isNotBlank() })
                    editingBookmark = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingBookmark = null }) { Text("Cancel") }
            },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Bookmarks",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )

            if (bookmarks.isEmpty()) {
                Text(
                    text = "No bookmarks yet. Tap the bookmark icon while reading to add one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
                )
            } else {
                LazyColumn {
                    items(sorted, key = { it.id }) { bookmark ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    onBookmarkDelete(bookmark.id)
                                    true
                                } else false
                            },
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            enableDismissFromStartToEnd = false,
                            backgroundContent = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.errorContainer)
                                        .padding(end = 20.dp),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    Icon(
                                        Icons.Default.Delete, "Delete",
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .clickable { onBookmarkClick(bookmark) }
                                    .padding(horizontal = 24.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Bookmark, contentDescription = "Bookmark",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(
                                        text = bookmark.label ?: formatVaultBookmarkLabel(bookmark.positionRef),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    bookmark.note?.takeIf { it.isNotBlank() }?.let { note ->
                                        Text(
                                            text = note,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        noteText = bookmark.note ?: ""
                                        editingBookmark = bookmark
                                    },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Edit, contentDescription = "Edit note",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
