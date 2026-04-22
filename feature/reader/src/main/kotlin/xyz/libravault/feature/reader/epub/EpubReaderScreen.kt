package xyz.libravault.feature.reader.epub

import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import xyz.libravault.core.domain.model.Bookmark
import xyz.libravault.core.domain.model.Highlight
import xyz.libravault.feature.reader.ReaderSettings
import xyz.libravault.feature.reader.ScrollMode

/**
 * EPUB reader screen.
 *
 * TODO: Full Readium 3.x integration pending API stabilisation.
 * The Readium Kotlin Toolkit 3.0.0-beta.2 API surface changed significantly
 * from earlier betas. Integration points:
 *   - Readium.assetRetriever.retrieve(url: AbsoluteUrl)
 *   - Readium.streamer.open(asset, allowUserInteraction)
 *   - EpubNavigatorFactory(publication).createFragmentFactory(...)
 *   - Fragment hosted via FragmentContainerView in AndroidView
 *
 * Position tracking: Locator → CFI string → passed to onPositionChanged
 * Tap zones: left/centre/right thirds handled by TapZoneOverlay below
 */
@Composable
fun EpubReaderScreen(
    fileUri: Uri,
    initialCfi: String?,
    settings: ReaderSettings,
    bookmarks: List<Bookmark>,
    highlights: List<Highlight>,
    fragmentManager: androidx.fragment.app.FragmentManager,
    onPositionChanged: (String) -> Unit,
    onCentreTap: () -> Unit,
    onAddHighlight: (positionRef: String, selectedText: String) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp

    Box(modifier = Modifier.fillMaxSize()) {
        // Placeholder until Readium integration is complete
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "EPUB reader\n(Readium integration in progress)",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }

        // Tap zone overlay — left/centre/right thirds
        TapZoneOverlay(
            screenWidthDp = screenWidthDp,
            onLeftTap     = { /* goBackward */ },
            onCentreTap   = onCentreTap,
            onRightTap    = { /* goForward */ },
            modifier      = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun TapZoneOverlay(
    screenWidthDp: androidx.compose.ui.unit.Dp,
    onLeftTap: () -> Unit,
    onCentreTap: () -> Unit,
    onRightTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val leftBoundary  = screenWidthDp * 0.33f
    val rightBoundary = screenWidthDp * 0.67f

    Box(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures { offset ->
                val xDp = offset.x / density
                when {
                    xDp < leftBoundary.value  -> onLeftTap()
                    xDp > rightBoundary.value -> onRightTap()
                    else                       -> onCentreTap()
                }
            }
        },
    )
}
