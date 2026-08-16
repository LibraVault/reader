package xyz.libravault.feature.reader.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import xyz.libravault.core.domain.model.Bookmark
import xyz.libravault.core.ui.theme.ReadingTheme
import xyz.libravault.feature.reader.FontFamily
import xyz.libravault.feature.reader.ReaderSettings
import xyz.libravault.feature.reader.ScrollMode
import xyz.libravault.feature.reader.markdown.toc.TocEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTopBar(
    title: String,
    onBack: () -> Unit,
    onFontDecrease: () -> Unit,
    onFontIncrease: () -> Unit,
    onAddBookmark: () -> Unit,
    onShowBookmarks: () -> Unit,
    onSettings: () -> Unit,
    showFontControls: Boolean = true,
    onShowToc: (() -> Unit)? = null,
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            if (showFontControls) {
                IconButton(onClick = onFontDecrease, modifier = Modifier.size(38.dp)) {
                    Text(
                        text = "A-",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                IconButton(onClick = onFontIncrease, modifier = Modifier.size(38.dp)) {
                    Text(
                        text = "A+",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            onShowToc?.let { showToc ->
                IconButton(onClick = showToc, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Table of contents")
                }
            }
            IconButton(onClick = onAddBookmark, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.BookmarkAdd, contentDescription = "Add bookmark")
            }
            IconButton(onClick = onShowBookmarks, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.Bookmark, contentDescription = "Bookmarks")
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.Settings, contentDescription = "Reader settings")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    settings: ReaderSettings,
    showFontControls: Boolean,
    onThemeChanged: (ReadingTheme) -> Unit,
    onFontSizeChanged: (Float) -> Unit,
    onFontFamilyChanged: (FontFamily) -> Unit,
    onLineSpacingChanged: (Float) -> Unit,
    onScrollModeChanged: (ScrollMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Reading settings", style = MaterialTheme.typography.headlineSmall)

            // ── Theme ──────────────────────────────────────────────────────
            Text("Theme", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadingTheme.entries.forEach { theme ->
                    FilterChip(
                        selected = settings.theme == theme,
                        onClick  = { onThemeChanged(theme) },
                        label    = { Text(theme.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }

            if (showFontControls) {
                HorizontalDivider()

                // ── Font size ──────────────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.TextFields, contentDescription = "Text formatting", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.weight(1f))
                    Text("${(settings.fontSize * 100).toInt()}%",
                        style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    value = settings.fontSize,
                    onValueChange = onFontSizeChanged,
                    valueRange = 0.8f..2.0f,
                    steps = 11,
                )

                HorizontalDivider()

                // ── Line spacing ───────────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Line spacing", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text("%.1f×".format(settings.lineSpacing),
                        style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    value = settings.lineSpacing,
                    onValueChange = onLineSpacingChanged,
                    valueRange = 1.0f..2.5f,
                    steps = 14,
                )

                HorizontalDivider()

                // ── Font family ────────────────────────────────────────────
                Text("Font", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    FontFamily.entries.forEach { family ->
                        FilterChip(
                            selected = settings.fontFamily == family,
                            onClick  = { onFontFamilyChanged(family) },
                            label    = { Text(family.displayName) },
                        )
                    }
                }
            }

            HorizontalDivider()

            // ── Scroll mode ────────────────────────────────────────────────
            Text("Mode", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScrollMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.scrollMode == mode,
                        onClick  = { onScrollModeChanged(mode) },
                        label    = {
                            Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                        },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun positionRefSortKey(ref: String): Long = when {
    ref.startsWith("ms:")     -> ref.removePrefix("ms:").toLongOrNull() ?: Long.MAX_VALUE
    ref.startsWith("page:")   -> (ref.removePrefix("page:").toIntOrNull() ?: Int.MAX_VALUE).toLong()
    ref.startsWith("scroll:") -> ref.removePrefix("scroll:").toLongOrNull() ?: Long.MAX_VALUE
    ref.startsWith("{")     -> runCatching {
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

private fun formatBookmarkLabel(positionRef: String): String = when {
    positionRef.startsWith("page:") ->
        positionRef.removePrefix("page:").toIntOrNull()
            ?.let { "Page ${it + 1}" } ?: positionRef
    positionRef.startsWith("scroll:") -> "Scroll position"
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksSheet(
    bookmarks: List<Bookmark>,
    onBookmarkClick: (Bookmark) -> Unit,
    onBookmarkDelete: (Long) -> Unit,
    onEditNote: (Long, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sorted = remember(bookmarks) { bookmarks.sortedBy { positionRefSortKey(it.positionRef) } }
    var editingBookmark by remember { mutableStateOf<Bookmark?>(null) }
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
                            }
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
                                    Icon(Icons.Default.Delete, "Delete",
                                        tint = MaterialTheme.colorScheme.onErrorContainer)
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
                                Icon(Icons.Default.Bookmark, contentDescription = "Bookmark",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp))
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 12.dp),
                                ) {
                                    Text(
                                        text = bookmark.label ?: formatBookmarkLabel(bookmark.positionRef),
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
                                    Icon(Icons.Default.Edit, contentDescription = "Edit note",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

/**
 * Table of contents sheet for the Markdown reader — the first TOC UI in the app
 * (neither EPUB nor PDF has one). Mirrors [BookmarksSheet]'s `ModalBottomSheet`
 * pattern; indentation reflects each entry's heading level (H1..H6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkdownTocSheet(
    entries: List<TocEntry>,
    onEntryClick: (TocEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Contents",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )

            if (entries.isEmpty()) {
                Text(
                    text = "No headings found in this document.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
                )
            } else {
                LazyColumn {
                    items(entries, key = { it.sectionIndex }) { entry ->
                        Text(
                            text = entry.title,
                            style = when (entry.level) {
                                1 -> MaterialTheme.typography.titleMedium
                                2 -> MaterialTheme.typography.titleSmall
                                else -> MaterialTheme.typography.bodyMedium
                            },
                            fontWeight = if (entry.level == 1) FontWeight.Medium else FontWeight.Normal,
                            color = if (entry.level <= 2) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onEntryClick(entry) }
                                .padding(
                                    start = 24.dp + 16.dp * (entry.level - 1),
                                    end = 24.dp,
                                    top = 12.dp,
                                    bottom = 12.dp,
                                ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
