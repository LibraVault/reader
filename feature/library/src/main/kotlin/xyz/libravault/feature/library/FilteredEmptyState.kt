package xyz.libravault.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.core.ui.theme.Dimens
import xyz.libravault.feature.library.R

/**
 * Shown in place of the grid when a format filter chip is active but matches nothing —
 * most commonly MD, since most vaults have no Markdown files at all. Deliberately no CTA
 * button (unlike [EmptyLibrary]): the filter chips stay visible directly above this, so
 * clearing the filter is already one tap away.
 */
@Composable
internal fun FilteredEmptyState(formatFilter: String?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.spaceXl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
            modifier = Modifier.padding(horizontal = Dimens.spaceXl),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
            Text(
                text = stringResource(formatFilterEmptyMessageRes(formatFilter)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Maps a [xyz.libravault.feature.library.LibraryViewModel] format-filter value — a
 * [MediaFormat.name], or the pseudo-formats "AUDIO"/"BOOK" — to the string resource
 * explaining why the grid is empty. Kept as a plain function (not @Composable) so it's
 * unit-testable without a Compose host; the caller resolves it via [stringResource].
 */
internal fun formatFilterEmptyMessageRes(filter: String?): Int = when (filter) {
    MediaFormat.EPUB.name -> R.string.empty_filter_epub
    MediaFormat.PDF.name -> R.string.empty_filter_pdf
    MediaFormat.MARKDOWN.name -> R.string.empty_filter_markdown
    "AUDIO" -> R.string.empty_filter_audio
    "BOOK" -> R.string.empty_filter_book
    else -> R.string.empty_filter_generic
}
