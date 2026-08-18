package xyz.libravault.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.core.ui.theme.Dimens

// ── Format filter helper ──────────────────────────────────────────────────────

internal fun List<LibraryItem>.applyFormatFilter(filter: String?): List<LibraryItem> = when (filter) {
    null   -> this
    "AUDIO" -> filter { it.format.isAudio() }
    "BOOK"  -> filter { !it.format.isAudio() }
    else   -> filter { it.format.name == filter }
}

// ── Format filter chips for search ────────────────────────────────────────────

@Composable
internal fun FormatFilterRow(
    currentFilter: String?,
    onFilterChanged: (String?) -> Unit,
    contentPadding: PaddingValues = PaddingValues(horizontal = Dimens.spaceLg, vertical = Dimens.spaceXs),
) {
    LazyRow(
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
    ) {
        item {
            FilterChip(
                selected = currentFilter == null,
                onClick = { onFilterChanged(null) },
                label = { Text("All") },
            )
        }
        item {
            FilterChip(
                selected = currentFilter == MediaFormat.EPUB.name,
                onClick = { onFilterChanged(MediaFormat.EPUB.name) },
                label = { Text("EPUB") },
            )
        }
        item {
            FilterChip(
                selected = currentFilter == MediaFormat.PDF.name,
                onClick = { onFilterChanged(MediaFormat.PDF.name) },
                label = { Text("PDF") },
            )
        }
        item {
            FilterChip(
                selected = currentFilter == MediaFormat.MARKDOWN.name,
                onClick = { onFilterChanged(MediaFormat.MARKDOWN.name) },
                label = { Text("MD") },
            )
        }
        item {
            FilterChip(
                selected = currentFilter == "AUDIO",
                onClick = { onFilterChanged("AUDIO") },
                label = { Text("Listening") },
            )
        }
    }
}
