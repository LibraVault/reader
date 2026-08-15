package xyz.libravault.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.libravault.core.ui.theme.AgedBrass
import xyz.libravault.core.ui.theme.DarkSurface0
import xyz.libravault.core.ui.theme.DarkSurface1
import xyz.libravault.core.ui.theme.DarkSurface2
import xyz.libravault.core.ui.theme.LeatherBrown
import xyz.libravault.core.ui.theme.LeatherDark
import xyz.libravault.core.ui.theme.LeatherLight
import xyz.libravault.core.ui.theme.WarmNeutral500
import xyz.libravault.core.ui.theme.WarmNeutral700

/**
 * Pairs of (dark, light) colors used as gradient stops. The pair is selected
 * deterministically by hashing the title, so the same book always gets the
 * same cover — even across recompositions and process restarts.
 *
 * Order is deliberate; index 0 is the deepest/most-subdued pair, index 7 the
 * most-saturated. Hash modulo len picks the pair.
 */
internal val CoverPalette: List<Pair<Color, Color>> = listOf(
    LeatherDark   to LeatherBrown,
    DarkSurface2  to LeatherBrown,
    LeatherDark   to WarmNeutral700,
    DarkSurface1  to LeatherBrown,
    WarmNeutral700 to LeatherBrown,
    DarkSurface2  to AgedBrass,
    LeatherDark   to AgedBrass,
    WarmNeutral500 to LeatherBrown,
)

/** Stable title → palette index. Same input always yields same output. */
internal fun paletteIndexFor(title: String): Int {
    if (title.isEmpty()) return 0
    val h = title.hashCode()
    // Mask to non-negative and modulo
    val mask = h and 0x7FFFFFFF
    return mask % CoverPalette.size
}

/** Uppercase initials: first letters of the first two words, or first two letters. */
internal fun initialsFor(title: String): String {
    val trimmed = title.trim()
    if (trimmed.isEmpty()) return "?"
    val words = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        words.size >= 2 -> (words[0].first().toString() + words[1].first()).uppercase()
        else            -> trimmed.take(2).uppercase()
    }
}

/** Content description applied to every [GeneratedCover] — also asserted on directly
 * by tests, and reused verbatim by callers (e.g. `feature:vault`'s padlock-badged
 * variant) that layer their own meaning on top without duplicating the string. */
const val NO_COVER_ART_DESCRIPTION: String = "No cover art"

/** Below this width the caption band shows the icon alone — "No cover art" doesn't
 * fit legibly at, say, the 40dp MiniPlayerBar thumbnail size. */
private val MIN_WIDTH_FOR_LABEL: Dp = 72.dp

/**
 * Deterministic two-tone cover-art placeholder used when a real cover image is
 * missing. Pairs a diagonal gradient (hashed from the title) with 2-letter initials
 * in the serif display type, plus an explicit "No cover art" caption band along the
 * bottom edge (icon-only below [MIN_WIDTH_FOR_LABEL]).
 *
 * The caption band exists because the gradient+initials alone can read as a real,
 * deliberately-designed cover rather than a stand-in for a missing one — see
 * https://github.com/LibraVault/reader/issues/168. It replaces the previous
 * ambiguous-by-itself treatment outright rather than adding a second, separate
 * "no cover" component, so every existing caller gets the fix for free.
 *
 * The cover container's shape is unchanged — the caller should wrap with its own
 * [Modifier.clip] if a rounded card is desired.
 */
@Composable
fun GeneratedCover(
    title: String,
    modifier: Modifier = Modifier,
    initialStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.headlineSmall,
) {
    val (top, bottom) = remember(title) { CoverPalette[paletteIndexFor(title)] }
    val initials = remember(title) { initialsFor(title) }
    val brush = remember(top, bottom) {
        Brush.linearGradient(
            colors = listOf(top, bottom),
        )
    }
    BoxWithConstraints(
        modifier = modifier
            .background(brush)
            .semantics { contentDescription = NO_COVER_ART_DESCRIPTION },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = initialStyle.copy(fontWeight = FontWeight.Bold),
            color = LeatherLight.copy(alpha = 0.92f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Visible,
        )

        val showLabel = maxWidth >= MIN_WIDTH_FOR_LABEL
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.38f))
                .padding(vertical = 3.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.ImageNotSupported,
                contentDescription = null, // the band's meaning is already on the outer semantics node
                tint = LeatherLight.copy(alpha = 0.9f),
                modifier = Modifier.size(12.dp),
            )
            if (showLabel) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = NO_COVER_ART_DESCRIPTION,
                    style = MaterialTheme.typography.labelSmall,
                    color = LeatherLight.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
