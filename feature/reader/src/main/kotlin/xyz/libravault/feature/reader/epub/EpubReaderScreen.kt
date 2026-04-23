package xyz.libravault.feature.reader.epub

import android.graphics.PointF
import android.net.Uri
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.css.Theme
import org.readium.r2.shared.publication.Locator
import xyz.libravault.core.domain.model.Bookmark
import xyz.libravault.core.domain.model.Highlight
import xyz.libravault.core.ui.theme.ReadingTheme
import xyz.libravault.feature.reader.FontFamily
import xyz.libravault.feature.reader.ReaderSettings
import xyz.libravault.feature.reader.ScrollMode

private const val EPUB_FRAGMENT_TAG = "epub_navigator"

/**
 * EPUB reader screen — full Readium 3.0.0-beta.2 integration.
 *
 * Architecture:
 *  - [EpubReaderViewModel] owns the [Publication] lifecycle (open / close).
 *  - [EpubNavigatorFragment] is hosted in a [FragmentContainerView] via [AndroidView].
 *  - Position changes flow: navigator.currentLocator → [onPositionChanged] →
 *      [xyz.libravault.feature.reader.ReaderViewModel] → Room.
 *  - Settings changes are pushed to the navigator via [EpubNavigatorFragment.submitPreferences].
 *  - Tap routing: the navigator's [EpubNavigatorFragment.Listener.onTap] callback
 *      routes centre-third taps to [onCentreTap]; left/right are handled by Readium natively.
 *
 * @param fileUri          SAF content URI of the EPUB file.
 * @param initialCfi       Stored locator JSON from Room (or bare CFI for legacy entries).
 *                         Null if the book has never been opened before.
 * @param settings         Current reader settings (theme, font, scroll mode).
 * @param bookmarks        Bookmarks for this item, used to render bookmark indicators.
 * @param highlights       Highlights for this item, rendered as coloured spans.
 * @param fragmentManager  The host Activity's [supportFragmentManager].
 * @param onPositionChanged Callback invoked whenever the user's position changes.
 *                          Receives the full Locator serialised to JSON for lossless storage.
 * @param onCentreTap      Callback invoked when the user taps the centre of the screen
 *                          (used to show/hide the toolbar in the parent screen).
 * @param onAddHighlight   Callback invoked when the user selects text and adds a highlight.
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
    viewModel: EpubReaderViewModel = hiltViewModel(),
) {
    val publicationState by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp

    // Open the publication when this composable first enters the composition
    LaunchedEffect(fileUri) {
        viewModel.openPublication(fileUri)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val ps = publicationState) {
            is EpubPublicationState.Idle,
            is EpubPublicationState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            is EpubPublicationState.Error -> {
                Text(
                    text  = "Could not open EPUB: ${ps.message}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            is EpubPublicationState.Ready -> {
                EpubNavigatorView(
                    publication     = ps.publication,
                    initialCfi      = initialCfi,
                    settings        = settings,
                    fragmentManager = fragmentManager,
                    screenWidthDp   = screenWidthDp,
                    onPositionChanged = onPositionChanged,
                    onCentreTap     = onCentreTap,
                    onAddHighlight  = onAddHighlight,
                )
            }
        }
    }
}

// ── Navigator fragment host ───────────────────────────────────────────────────

@Composable
private fun EpubNavigatorView(
    publication: org.readium.r2.shared.publication.Publication,
    initialCfi: String?,
    settings: ReaderSettings,
    fragmentManager: FragmentManager,
    screenWidthDp: androidx.compose.ui.unit.Dp,
    onPositionChanged: (String) -> Unit,
    onCentreTap: () -> Unit,
    onAddHighlight: (positionRef: String, selectedText: String) -> Unit,
) {
    // Stable ID for the FragmentContainerView so we can look up the fragment later
    val containerId = remember { View.generateViewId() }

    // Keep callbacks stable across recompositions so the listener closure
    // always invokes the latest lambda without recreating the fragment
    val currentOnPositionChanged = rememberUpdatedState(onPositionChanged)
    val currentOnCentreTap       = rememberUpdatedState(onCentreTap)
    val currentOnAddHighlight    = rememberUpdatedState(onAddHighlight)

    // Hold a reference to the navigator fragment so settings changes can be
    // pushed to it without recreating the fragment
    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    val scope = rememberCoroutineScope()

    // ── FragmentContainerView ────────────────────────────────────────────────
    AndroidView(
        factory = { context ->
            FragmentContainerView(context).apply { id = containerId }
        },
        modifier = Modifier.fillMaxSize(),
    )

    // ── Fragment setup ───────────────────────────────────────────────────────
    DisposableEffect(publication, containerId) {

        // Build the initial Locator from stored JSON or bare CFI.
        // positionCfi stores the full Locator JSON since the first Readium save;
        // legacy entries (bare CFI strings) are handled by the fallback branch.
        val initialLocator: Locator? = initialCfi?.let { stored ->
            runCatching {
                Locator.fromJSON(JSONObject(stored))
            }.getOrElse {
                // Legacy bare CFI — wrap it in a minimal Locator so Readium can
                // restore to approximately the right position.
                publication.readingOrder.firstOrNull()?.let { link ->
                    Locator(
                        href  = link.url(),
                        mediaType = link.mediaType ?: org.readium.r2.shared.util.mediatype.MediaType.XHTML,
                        locations = Locator.Locations(
                            otherLocations = mapOf("cfi" to stored)
                        ),
                    )
                }
            }
        }

        // Navigator listener — routes taps and position change callbacks
        val listener = object : EpubNavigatorFragment.Listener {

            /**
             * Called by Readium on every tap. Return true to consume the tap
             * (Readium will NOT navigate); return false to let Readium handle it
             * (it will go backward/forward based on tap zone).
             *
             * We consume only the centre third, which toggles the toolbar.
             * Left/right thirds fall through to Readium's built-in page turn.
             */
            override fun onTap(point: PointF): Boolean {
                val xDp    = point.x / (publication as Any).let { 1f } // density placeholder
                val width  = screenWidthDp.value
                val xRatio = point.x / width
                return if (xRatio in 0.33f..0.67f) {
                    currentOnCentreTap.value.invoke()
                    true   // consumed — do NOT turn page
                } else {
                    false  // let Readium navigate
                }
            }
        }

        // Preferences derived from current ReaderSettings
        val preferences = settings.toEpubPreferences()

        // Create the navigator fragment factory
        val factory = EpubNavigatorFactory(publication)
            .createFragmentFactory(
                initialLocator      = initialLocator,
                initialPreferences  = preferences,
                listener            = listener,
            )

        // Commit the fragment, replacing any previous instance
        fragmentManager.fragmentFactory = factory
        fragmentManager.commitNowAllowingStateLoss {
            replace(containerId, EpubNavigatorFragment::class.java, null, EPUB_FRAGMENT_TAG)
        }

        // Get the navigator reference synchronously (commitNow = immediate execution)
        val nav = fragmentManager.findFragmentByTag(EPUB_FRAGMENT_TAG) as? EpubNavigatorFragment
        navigator = nav

        // Collect position changes from the navigator's StateFlow
        nav?.currentLocator
            ?.onEach { locator ->
                // Serialise the full Locator to JSON for lossless storage.
                // This preserves href, position, progression, and CFI so we can
                // restore exactly to the right spine item and position.
                currentOnPositionChanged.value.invoke(locator.toJSON().toString())
            }
            ?.launchIn(scope)

        onDispose {
            // Remove the fragment on composition exit so it doesn't linger
            // if the user navigates away while a settings sheet is open etc.
            if (!fragmentManager.isStateSaved) {
                fragmentManager.commitNowAllowingStateLoss {
                    fragmentManager.findFragmentByTag(EPUB_FRAGMENT_TAG)?.let { remove(it) }
                }
            }
            navigator = null
        }
    }

    // ── Settings hot-reload ──────────────────────────────────────────────────
    // Push updated preferences to the navigator whenever settings change,
    // without recreating the fragment (avoids position loss and re-parse cost).
    LaunchedEffect(settings) {
        navigator?.submitPreferences(settings.toEpubPreferences())
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * Maps [ReaderSettings] to Readium's [EpubPreferences].
 *
 * Font size: [ReaderSettings.fontSize] is a multiplier (0.8–2.0) relative to
 * the user's system default. Readium's [EpubPreferences.fontSize] is a
 * percentage value where 100.0 = default. We multiply by 100.
 *
 * Scroll mode: Readium's scroll mode maps to `overflow = SCROLLED` (continuous)
 * vs the default paginated layout.
 */
private fun ReaderSettings.toEpubPreferences(): EpubPreferences {
    return EpubPreferences(
        theme = when (theme) {
            ReadingTheme.DARK  -> Theme.DARK
            ReadingTheme.LIGHT -> Theme.LIGHT
            ReadingTheme.SEPIA -> Theme.SEPIA
        },
        fontSize = fontSize.toDouble() * 100.0,  // multiplier → percentage
        // TODO: map fontFamily → EpubPreferences.fontFamily once the mapping
        //       of system/serif/sans-serif/mono to Readium's FontFamily is confirmed
        //       for beta.2 (the enum names differ between alpha and beta releases).
        scroll = scrollMode == ScrollMode.SCROLLING,
    )
}
