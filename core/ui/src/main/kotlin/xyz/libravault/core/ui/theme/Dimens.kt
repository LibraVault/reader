package xyz.libravault.core.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Spacing scale — strict 4 pt grid. Anything outside this set is a bug.
 */
object Dimens {
    val spaceXs  = 4.dp
    val spaceSm  = 8.dp
    val spaceMd  = 12.dp
    val spaceLg  = 16.dp
    val spaceXl  = 24.dp
    val spaceXxl = 32.dp

    /** Fixed cover-art width used in library rows. */
    val coverWidth  = 120.dp
    const val coverAspect = 2f / 3f

    /** Mini-player / reader mini-bar height. */
    val miniBarHeight = 64.dp

    /** Standard top-app-bar height (matches Material default). */
    val topBarHeight = 56.dp
}