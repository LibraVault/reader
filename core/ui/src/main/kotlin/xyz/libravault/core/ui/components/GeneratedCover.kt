package xyz.libravault.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * Deterministic two-tone cover-art placeholder used when a real cover image
 * is missing. Pairs a diagonal gradient (hashed from the title) with 2-letter
 * initials in the serif display type. Designed to replace the empty brown
 * rectangles that previously appeared for audiobook files without embedded art.
 *
 * The cover container's shape is unchanged — the caller should wrap with its
 * own [Modifier.clip] if a rounded card is desired.
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
    Box(
        modifier = modifier.background(brush),
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
    }
}