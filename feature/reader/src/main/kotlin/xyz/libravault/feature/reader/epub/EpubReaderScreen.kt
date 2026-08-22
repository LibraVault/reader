package xyz.libravault.feature.reader.epub

import android.graphics.Color as AndroidColor
import android.net.Uri
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.*
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.html.HtmlDecorationTemplates
import org.readium.r2.navigator.input.DragEvent as ReadiumDragEvent
import org.readium.r2.navigator.input.InputListener as ReadiumInputListener
import org.readium.r2.navigator.input.KeyEvent as ReadiumKeyEvent
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.FontFamily as ReadiumFontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import xyz.libravault.core.domain.model.Bookmark
import xyz.libravault.core.domain.model.Highlight
import xyz.libravault.core.ui.theme.ReadingTheme
import xyz.libravault.core.ui.theme.resolved
import xyz.libravault.feature.reader.FontFamily
import xyz.libravault.feature.reader.ReaderSettings
import xyz.libravault.feature.reader.ScrollMode

private const val EPUB_FRAGMENT_TAG = "epub_navigator"
private const val DECORATION_GROUP_HIGHLIGHTS = "highlights"
private val HIGHLIGHT_COLORS = listOf(
    "#FFE066" to "Yellow",
    "#90EE90" to "Green",
    "#87CEEB" to "Blue",
    "#FFB6C1" to "Pink",
)

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
    onAddHighlight: (positionRef: String, selectedText: String, colorHex: String) -> Unit,
    viewModel: EpubReaderViewModel = hiltViewModel(),  // caller may pass its own instance
) {
    val publicationState by viewModel.state.collectAsState()
    val pendingLocator   by viewModel.pendingLocator.collectAsState()

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

            is EpubPublicationState.DrmProtected -> {
                Text(
                    text  = "This book is protected and can't be opened" +
                        (ps.schemeName?.let { " (protected by $it)" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }

            is EpubPublicationState.Ready -> {
                EpubNavigatorView(
                    publication              = ps.publication,
                    initialCfi               = initialCfi,
                    settings                 = settings,
                    highlights               = highlights,
                    pendingLocator           = pendingLocator,
                    onPendingLocatorConsumed = viewModel::clearPendingLocator,
                    fragmentManager          = fragmentManager,
                    onPositionChanged        = onPositionChanged,
                    onLocatorChanged         = viewModel::onLocatorChanged,
                    onCentreTap              = onCentreTap,
                    onAddHighlight           = onAddHighlight,
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
    highlights: List<Highlight>,
    pendingLocator: Locator?,
    onPendingLocatorConsumed: () -> Unit,
    fragmentManager: FragmentManager,
    onPositionChanged: (String) -> Unit,
    onLocatorChanged: (Locator) -> Unit,
    onCentreTap: () -> Unit,
    onAddHighlight: (positionRef: String, selectedText: String, colorHex: String) -> Unit,
) {
    val context = LocalContext.current
    // Stable ID for the FragmentContainerView so we can look up the fragment later
    val containerId = remember { View.generateViewId() }
    // Read once per composition so ReadingTheme.SYSTEM resolves consistently at both
    // toEpubPreferences() call sites below; recomposes on its own if the OS appearance
    // changes while this screen is open (isSystemInDarkTheme() reads LocalConfiguration).
    val systemInDarkTheme = isSystemInDarkTheme()

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

    var showColorPicker    by remember { mutableStateOf(false) }
    var pendingLocatorJson by remember { mutableStateOf("") }
    var pendingText        by remember { mutableStateOf("") }

    // ── FragmentContainerView + color-picker overlay ─────────────────────────
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                FragmentContainerView(context).apply { id = containerId }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (showColorPicker) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable {
                            scope.launch { navigator?.clearSelection() }
                            showColorPicker = false
                        },
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    shadowElevation = 8.dp,
                    modifier = Modifier.clickable(onClick = {}),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HIGHLIGHT_COLORS.forEach { (colorHex, _) ->
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(
                                        color  = Color(AndroidColor.parseColor(colorHex)),
                                        shape  = CircleShape,
                                    )
                                    .clickable {
                                        val lj = pendingLocatorJson
                                        val pt = pendingText
                                        if (lj.isNotEmpty()) {
                                            currentOnAddHighlight.value(lj, pt, colorHex)
                                            scope.launch { navigator?.clearSelection() }
                                        }
                                        showColorPicker = false
                                    }
                            )
                        }
                    }
                }
            }
        }
    }

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
        val preferences = settings.toEpubPreferences(systemInDarkTheme)

        val selectionCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                scope.launch {
                    val sel = navigator?.currentSelection() ?: return@launch
                    pendingLocatorJson = sel.locator.toJSON().toString()
                    pendingText        = sel.locator.text?.highlight ?: ""
                    showColorPicker    = true
                    // Finish after saving selection so the action mode state machine
                    // resets cleanly — returning false leaves it stuck and blocks
                    // subsequent selections.
                    mode.finish()
                }
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
            override fun onDestroyActionMode(mode: ActionMode) {}
        }

        val config = EpubNavigatorFragment.Configuration().apply {
            selectionActionModeCallback = selectionCallback
            decorationTemplates = HtmlDecorationTemplates.defaultTemplates()
            useReadiumCssFontSize = true
        }

        // Create the navigator fragment factory
        val factory = EpubNavigatorFactory(publication)
            .createFragmentFactory(
                initialLocator      = initialLocator,
                initialPreferences  = preferences,
                listener            = listener,
                configuration       = config,
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

    // ── Bookmark navigation ──────────────────────────────────────────────────
    // Navigate to a locator set by the user tapping a bookmark in the sheet.
    LaunchedEffect(pendingLocator) {
        val locator = pendingLocator ?: return@LaunchedEffect
        navigator?.go(locator, animated = false)
        onPendingLocatorConsumed()
    }

    // ── Settings hot-reload ──────────────────────────────────────────────────
    // Push updated preferences to the navigator whenever settings, systemInDarkTheme, or
    // the navigator itself changes. Keying on both settings and navigator ensures
    // preferences are applied immediately after the fragment is committed (navigator
    // starts null and is set inside DisposableEffect, so LaunchedEffect(settings) alone
    // would miss the first apply); systemInDarkTheme is keyed separately so a
    // ReadingTheme.SYSTEM selection re-resolves and re-applies live when the OS appearance
    // changes while the reader is open (#370's "updates live" acceptance criterion).
    LaunchedEffect(settings, systemInDarkTheme, navigator) {
        navigator?.submitPreferences(settings.toEpubPreferences(systemInDarkTheme))
    }

    // ── Highlight decorations ────────────────────────────────────────────────
    // Re-apply all stored highlights whenever the highlight list or navigator changes.
    // WS3.7 (review finding #18): explicit try/catch for IllegalArgumentException
    // (bad color hex) and JSONException (corrupt locator JSON) so we log the
    // highlight id, not silently drop it. Previously a `runCatching` swallowed
    // Error and Exception equally — a malformed color or a corrupt locator
    // JSON would make the entire highlight vanish with no breadcrumb.
    LaunchedEffect(navigator, highlights) {
        val nav = navigator ?: return@LaunchedEffect
        val decorations = highlights.mapNotNull { h ->
            try {
                val locator = Locator.fromJSON(JSONObject(h.positionRef))
                if (locator == null) {
                    Log.w("EpubReaderScreen", "highlight ${h.id}: Locator.fromJSON returned null; skipping")
                    return@mapNotNull null
                }
                Decoration(
                    id      = "h_${h.id}",
                    locator = locator,
                    style   = Decoration.Style.Highlight(
                        tint = AndroidColor.parseColor(h.colorHex),
                    ),
                )
            } catch (e: IllegalArgumentException) {
                Log.w("EpubReaderScreen", "highlight ${h.id}: bad color hex '${h.colorHex}': ${e.message}")
                null
            } catch (e: org.json.JSONException) {
                Log.w("EpubReaderScreen", "highlight ${h.id}: bad locator JSON: ${e.message}")
                null
            }
        }
        nav.applyDecorations(decorations, DECORATION_GROUP_HIGHLIGHTS)
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * Extra letter-spacing (in rem, Readium's [EpubPreferences.letterSpacing] unit)
 * applied automatically alongside [FontFamily.OPEN_DYSLEXIC] (#423) — dyslexia-
 * friendly typography guidance recommends generous letter-spacing alongside the
 * typeface itself, not the font alone. 0.125rem sits within readium-css's own
 * documented 0–0.5rem recommended range for `--USER__letterSpacing`
 * (https://readium.org/css/docs/CSS19-api.html); Readium's `RangePreference`
 * clamps to whatever the effective supported range actually is regardless, so
 * this stays safe even if that differs from the doc.
 */
private const val DYSLEXIA_FRIENDLY_LETTER_SPACING = 0.125

/**
 * Maps [ReaderSettings] to Readium's [EpubPreferences].
 *
 * Font size: [ReaderSettings.fontSize] is a multiplier (0.8–2.0) relative to
 * the user's system default. Readium's [EpubPreferences.fontSize] is a
 * percentage value where 100.0 = default. We multiply by 100.
 *
 * Scroll mode: Readium's scroll mode maps to `overflow = SCROLLED` (continuous)
 * vs the default paginated layout.
 *
 * [systemInDarkTheme] resolves [ReadingTheme.SYSTEM] (#370) — pass the caller's
 * `isSystemInDarkTheme()`; Readium's own [Theme] has no fourth "system" case, so this is
 * one of the call sites that must resolve before converting (same shape as
 * `mermaidThemeName` in the Mermaid package).
 *
 * AMOLED (#420): Readium's [Theme] enum also has no true-black case, so it maps to
 * [Theme.DARK] as its CSS base — but [EpubPreferences.backgroundColor]/[textColor] are
 * *separate* preferences layered on top of [theme] (unset = "current theme's background
 * color is effective", per Readium's own docs), so overriding them to pure black/white
 * only for AMOLED gets a real true-black page without needing a Readium-side theme.
 *
 * Margins/justification/hyphenation (#421): all three are native Readium EPUB
 * preferences on this pinned navigator version (confirmed via `EpubPreferences`'s
 * constructor — `pageMargins: Double?`, `textAlign: TextAlign?`, `hyphens: Boolean?`),
 * not hand-rolled CSS. [ReaderSettings.marginScale] maps straight to `pageMargins`
 * (both are 1.0-default multipliers — Readium's own "100%, no scaling" value). Justify
 * is offered as a single on/off control (matching the product ask) rather than exposing
 * [TextAlign]'s full START/END/LEFT/RIGHT/CENTER/JUSTIFY set; off leaves `textAlign`
 * unset so behaviour is unchanged until the user opts in.
 *
 * `internal` rather than `private` (AGENTS.md's pure-helper convention) so it's
 * directly unit-testable — see `EpubPreferencesMappingTest`/`EpubReaderScreenPreferencesTest`.
 */
@OptIn(ExperimentalReadiumApi::class)
internal fun ReaderSettings.toEpubPreferences(systemInDarkTheme: Boolean): EpubPreferences {
    val resolvedTheme = theme.resolved(systemInDarkTheme)
    val isAmoled = resolvedTheme == xyz.libravault.core.ui.theme.ConcreteReadingTheme.AMOLED
    return EpubPreferences(
        theme = when (resolvedTheme) {
            xyz.libravault.core.ui.theme.ConcreteReadingTheme.DARK   -> Theme.DARK
            xyz.libravault.core.ui.theme.ConcreteReadingTheme.LIGHT  -> Theme.LIGHT
            xyz.libravault.core.ui.theme.ConcreteReadingTheme.SEPIA  -> Theme.SEPIA
            xyz.libravault.core.ui.theme.ConcreteReadingTheme.AMOLED -> Theme.DARK
        },
        backgroundColor = if (isAmoled) ReadiumColor(0xFF000000.toInt()) else null,
        textColor       = if (isAmoled) ReadiumColor(0xFFFFFFFF.toInt()) else null,
        // Disable publisher CSS so our font/size/spacing overrides take effect.
        publisherStyles = false,
        // Readium's EpubPreferences.fontSize is a ratio stored in Length.Percent, whose
        // toCss() implementation already multiplies by 100 to produce the % string.
        // Pass the raw multiplier (0.8–2.0); do NOT multiply by 100 here or the WebView
        // receives values like "10000%" which browsers silently ignore.
        fontSize    = fontSize.toDouble(),
        lineHeight  = lineSpacing.toDouble(),
        pageMargins = marginScale.toDouble(),
        textAlign   = if (justifyText) TextAlign.JUSTIFY else null,
        hyphens     = hyphenation,
        fontFamily = when (fontFamily) {
            FontFamily.SERIF         -> ReadiumFontFamily.SERIF
            FontFamily.SANS_SERIF    -> ReadiumFontFamily.SANS_SERIF
            FontFamily.MONOSPACE     -> ReadiumFontFamily.MONOSPACE
            // Readium's EPUB navigator already embeds OpenDyslexic internally — no
            // FontFamilyDeclaration/servedAssets wiring needed, unlike a truly
            // custom family. See core/ui/licenses/README.md for the full story.
            FontFamily.OPEN_DYSLEXIC -> ReadiumFontFamily.OPEN_DYSLEXIC
            FontFamily.SYSTEM        -> null
        },
        letterSpacing = if (fontFamily == FontFamily.OPEN_DYSLEXIC) {
            DYSLEXIA_FRIENDLY_LETTER_SPACING
        } else {
            null
        },
        scroll = scrollMode == ScrollMode.SCROLLING,
    )
}
