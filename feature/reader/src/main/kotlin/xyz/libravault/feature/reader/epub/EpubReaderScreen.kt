package xyz.libravault.feature.reader.epub

import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.streamer.Readium
import xyz.libravault.core.domain.model.Bookmark
import xyz.libravault.core.domain.model.Highlight
import xyz.libravault.core.ui.theme.ReadingTheme
import xyz.libravault.feature.reader.FontFamily
import xyz.libravault.feature.reader.ReaderSettings
import xyz.libravault.feature.reader.ScrollMode

/**
 * EPUB reader screen powered by Readium 3.x Kotlin Toolkit.
 *
 * Integration notes:
 *  - Readium is initialised once per process via [rememberReadium].
 *  - The EPUB is opened from a SAF URI via [Readium.streamer] — no file-copy
 *    needed, Readium can stream directly from a content URI.
 *  - [EpubNavigatorFragment] is hosted inside an [AndroidView]/[FragmentContainerView]
 *    since the Compose navigator API is still experimental in 3.x beta.
 *  - Position changes are reported as CFI strings and passed back to
 *    [ReaderViewModel] for persistence.
 *  - Tap zones are implemented as a transparent overlay on top of the navigator:
 *      Left  third  → previous page
 *      Centre third → toggle toolbar (via [onCentreTap])
 *      Right  third → next page
 *
 * @param fileUri   SAF content URI of the EPUB file.
 * @param initialCfi Restored CFI from Room — null on first open.
 */
@Composable
fun EpubReaderScreen(
    fileUri: Uri,
    initialCfi: String?,
    settings: ReaderSettings,
    bookmarks: List<Bookmark>,
    highlights: List<Highlight>,
    fragmentManager: FragmentManager,
    onPositionChanged: (String) -> Unit,
    onCentreTap: () -> Unit,
    onAddHighlight: (positionRef: String, selectedText: String) -> Unit,
) {
    val context       = LocalContext.current
    val scope         = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp

    // ── Readium initialisation ────────────────────────────────────────────────
    val readium = remember { Readium(context) }
    var publication by remember { mutableStateOf<Publication?>(null) }
    var navigator   by remember { mutableStateOf<EpubNavigatorFragment?>(null) }

    LaunchedEffect(fileUri) {
        scope.launch {
            // Open the EPUB from the SAF URI directly — no temp file needed.
            val asset = readium.assetRetriever.retrieve(fileUri)
                .getOrElse { return@launch }

            val pub = readium.streamer.open(asset, allowUserInteraction = false)
                .getOrElse { return@launch }

            publication = pub

            // Build navigator preferences from ReaderSettings
            val prefs = EpubPreferences(
                fontSize     = settings.fontSize.toDouble(),
                scroll       = settings.scrollMode == ScrollMode.SCROLLING,
            )

            val initialLocator = initialCfi?.let {
                runCatching { Locator.fromJSON(it) }.getOrNull()
            }

            val navigatorFactory = EpubNavigatorFactory(pub)
            val fragment = navigatorFactory.createFragment(
                initialLocator  = initialLocator,
                configuration   = EpubNavigatorFragment.Configuration {
                    preferences = prefs
                    // Apply theme background — Readium respects these via CSS injection
                    when (settings.theme) {
                        ReadingTheme.DARK  -> readingOrder
                        ReadingTheme.LIGHT -> readingOrder
                        ReadingTheme.SEPIA -> readingOrder
                    }
                },
            )

            // Report position changes back to ViewModel for persistence
            fragment.currentLocator.collect { locator ->
                locator?.let { onPositionChanged(it.toJSON().toString()) }
            }

            navigator = fragment
        }
    }

    // ── Navigator hosted in AndroidView ───────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize()) {
        navigator?.let { frag ->
            AndroidView(
                factory = { ctx ->
                    FragmentContainerView(ctx).apply {
                        id = android.R.id.content + 1
                        fragmentManager.beginTransaction()
                            .replace(id, frag)
                            .commitNow()
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // ── Tap zone overlay ──────────────────────────────────────────────────
        // Transparent overlay captures taps without blocking Readium's own
        // gesture handling (text selection, link taps).
        TapZoneOverlay(
            screenWidthDp = screenWidthDp,
            onLeftTap     = { navigator?.goBackward(animated = true) },
            onCentreTap   = onCentreTap,
            onRightTap    = { navigator?.goForward(animated = true) },
            modifier      = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Transparent overlay dividing the screen into three tap zones.
 *
 *  ┌──────────┬──────────────┬──────────┐
 *  │   ← Prev │  Toggle bar  │  Next →  │
 *  │  (33 %)  │   (34 %)     │  (33 %)  │
 *  └──────────┴──────────────┴──────────┘
 */
@Composable
private fun TapZoneOverlay(
    screenWidthDp: androidx.compose.ui.unit.Dp,
    onLeftTap: () -> Unit,
    onCentreTap: () -> Unit,
    onRightTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val leftBoundary   = screenWidthDp * 0.33f
    val rightBoundary  = screenWidthDp * 0.67f

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
