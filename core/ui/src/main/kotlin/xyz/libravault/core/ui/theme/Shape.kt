package xyz.libravault.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material 3 shape tokens — keep radii consistent across the app.
 *
 *   cover     6 dp — small artwork (book/audio covers)
 *   card      14 dp — primary card surface
 *   sheet     20 dp — modal bottom sheet content
 *   xLarge    28 dp — large sheet corners (top edges on bottom sheets)
 *   chip      8 dp — chips and inline tags
 */
val LibravaultShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small      = RoundedCornerShape(6.dp),
    medium     = RoundedCornerShape(14.dp),
    large      = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)