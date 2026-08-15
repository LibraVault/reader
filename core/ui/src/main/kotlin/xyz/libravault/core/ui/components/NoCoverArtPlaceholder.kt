package xyz.libravault.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Below this width there isn't room for the "No cover art" label, so only the icon shows. */
internal val NoCoverArtLabelMinWidth = 96.dp

/**
 * Explicit "no cover art" state for library items with no embedded artwork.
 *
 * Deliberately muted (theme's `surfaceVariant`/`onSurfaceVariant`, not the warm
 * leather-toned [CoverPalette]) and icon-first, so it reads as "no artwork was
 * found" rather than as a deliberately designed cover — unlike a stylized
 * initials treatment, which can be mistaken for real, intentional cover art.
 */
@Composable
fun NoCoverArtPlaceholder(modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val showLabel = maxWidth >= NoCoverArtLabelMinWidth
        val tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.ImageNotSupported,
                // Redundant with the visible label when there's room for one.
                contentDescription = if (showLabel) null else "No cover art",
                tint = tint,
                modifier = Modifier.size(if (showLabel) 32.dp else 18.dp),
            )
            if (showLabel) {
                Text(
                    text = "No cover art",
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
