package xyz.libravault.feature.reader.epub

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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.*
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.DragEvent as ReadiumDragEvent
import org.readium.r2.navigator.input.InputListener as ReadiumInputListener
import org.readium.r2.navigator.input.KeyEvent as ReadiumKeyEvent
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
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
    viewModel: EpubReaderViewModel = hiltViewModel(),  // caller may pass its own instance
) {
    val publicationState by viewModel.state.collectAsState()

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
                    publication       = ps.publication,
                    initialCfi        = initialCfi,
                    settings          = settings,
                    fragmentManager   = fragmentManager,
                    onPositionChanged = onPositionChanged,
                    onLocatorChanged  = viewModel::onLocatorChanged,
                    onCentreTap       = onCentreTap,
                    onAddHighlight    = onAddHighlight,
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
    onPositionChanged: (String) -> Unit,
    onLocatorChanged: (Locator) -> Unit,
    onCentreTap: () -> Unit,
    onAddHighlight: (positionRef: String, selectedText: String) -> Unit,
) {
    // Stable ID for the FragmentContainerView so we can look up the fragment later
    val containerId = remember { View.generateViewId() }

    // Keep callbacks stable across recompositions so the listener closure
    // always invokes the latest lambda without recreating the fragment
    val currentOnPositionChanged = rememberUpdatedState(onPositionChanged)
    val currentOnLocatorChanged  = rememberUpdatedState(onLocatorChanged)
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
    @OptIn(ExperimentalReadiumApi::class)
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

        // Navigator listener — only handles hyperlink routing in beta.2.
        // Tap events are routed exclusively through InputListener (see below),
        // NOT through Listener.onTap(PointF) — that path was removed in beta.2.
        val listener = object : EpubNavigatorFragment.Listener {

            /** Required by [HyperlinkNavigator.Listener]. Allow all internal links. */
            @OptIn(ExperimentalReadiumApi::class)
            override fun shouldFollowInternalLink(
                link: org.readium.r2.shared.publication.Link,
                context: org.readium.r2.navigator.HyperlinkNavigator.LinkContext?
            ): Boolean = true

            /** Required by [HyperlinkNavigator.Listener]. External links are no-op in v1. */
            @OptIn(ExperimentalReadiumApi::class)
            override fun onExternalLinkActivated(url: org.readium.r2.shared.util.AbsoluteUrl) {
                // v1: no-op — Libravault is fully offline; no browser integration.
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
        fragmentManager.commitNow(allowStateLoss = true) {
            replace<EpubNavigatorFragment>(containerId, tag = EPUB_FRAGMENT_TAG)
        }

        // Get the navigator reference synchronously (commitNow = immediate execution)
        val nav = fragmentManager.findFragmentByTag(EPUB_FRAGMENT_TAG) as? EpubNavigatorFragment
        navigator = nav

        // Use Readium's own DirectionalNavigationAdapter for left/right page turns.
        // It uses the same coordinate space as TapEvent and handles RTL reading
        // progression correctly. We add it first so it claims edge taps (returns
        // true) and lets center taps fall through to the next listener.
        val dirNavAdapter = nav?.let { n ->
            DirectionalNavigationAdapter(navigator = n).also { n.addInputListener(it) }
        }

        // Center-tap listener: only fires when dirNavAdapter returned false (i.e.,
        // the tap was NOT in the left/right edge zone). Toggles the UI overlay.
        val centerTapListener = object : ReadiumInputListener {
            override fun onTap(event: TapEvent): Boolean {
                currentOnCentreTap.value.invoke()
                return true
            }
            override fun onDrag(event: ReadiumDragEvent): Boolean = false
            override fun onKey(event: ReadiumKeyEvent): Boolean = false
        }
        nav?.addInputListener(centerTapListener)

        // Collect position changes from the navigator's StateFlow
        nav?.currentLocator
            ?.onEach { locator ->
                // Serialise the full Locator to JSON for lossless storage.
                // This preserves href, position, progression, and CFI so we can
                // restore exactly to the right spine item and position.
                currentOnPositionChanged.value.invoke(locator.toJSON().toString())
                // Mirror the raw Locator so the ViewModel can use it for TTS chapter lookup.
                currentOnLocatorChanged.value.invoke(locator)
            }
            ?.launchIn(scope)

        onDispose {
            dirNavAdapter?.let { nav?.removeInputListener(it) }
            nav?.removeInputListener(centerTapListener)
            if (!fragmentManager.isStateSaved) {
                fragmentManager.commitNow(allowStateLoss = true) {
                    nav?.let { remove(it) }
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
@OptIn(ExperimentalReadiumApi::class)
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
