package xyz.libravault.feature.vault

import android.graphics.Color as AndroidColor
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.*
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
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import xyz.libravault.core.vaultstore.VaultHighlight

private const val VAULT_EPUB_FRAGMENT_TAG = "vault_epub_navigator"
private const val DECORATION_GROUP_HIGHLIGHTS = "highlights"

/** Same 4-color palette `feature:reader`'s `EpubReaderScreen` offers — kept
 * as a private duplicate rather than a new cross-module dependency, matching
 * how `feature:vault` already duplicates rather than reuses UI-only pieces
 * (see [VaultCoverPlaceholder]'s doc comment for the same call). */
private val VAULT_HIGHLIGHT_COLORS = listOf("#FFE066", "#90EE90", "#87CEEB", "#FFB6C1")

/**
 * Readium navigator host for vault EPUBs — paginated reading with left/right
 * tap-to-turn and centre-tap-to-toggle-toolbar. Supports bookmark navigation,
 * a text-selection color picker, and highlight decorations (issue: vault
 * bookmarks/highlights UI) — still deliberately smaller than `feature:reader`'s
 * `EpubNavigatorView` in one respect: no per-user `ReaderSettings` hot-reload
 * (separate follow-up).
 */
@Composable
fun VaultEpubReaderScreen(
    publication: Publication,
    fragmentManager: FragmentManager,
    highlights: List<VaultHighlight>,
    pendingLocatorJson: String?,
    onPendingLocatorConsumed: () -> Unit,
    onPositionChanged: (String) -> Unit,
    onCentreTap: () -> Unit,
    onAddHighlight: (positionRef: String, selectedText: String, colorHex: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerId = remember { View.generateViewId() }
    val currentOnCentreTap = rememberUpdatedState(onCentreTap)
    val currentOnPositionChanged = rememberUpdatedState(onPositionChanged)
    val currentOnAddHighlight = rememberUpdatedState(onAddHighlight)
    val scope = rememberCoroutineScope()

    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    var showColorPicker by remember { mutableStateOf(false) }
    var pendingLocatorForHighlight by remember { mutableStateOf("") }
    var pendingText by remember { mutableStateOf("") }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { context -> FragmentContainerView(context).apply { id = containerId } },
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
                        VAULT_HIGHLIGHT_COLORS.forEach { colorHex ->
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(color = Color(AndroidColor.parseColor(colorHex)), shape = CircleShape)
                                    .clickable {
                                        val lj = pendingLocatorForHighlight
                                        val pt = pendingText
                                        if (lj.isNotEmpty()) {
                                            currentOnAddHighlight.value(lj, pt, colorHex)
                                            scope.launch { navigator?.clearSelection() }
                                        }
                                        showColorPicker = false
                                    },
                            )
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalReadiumApi::class)
    DisposableEffect(publication, containerId) {
        val listener = object : EpubNavigatorFragment.Listener {
            @OptIn(ExperimentalReadiumApi::class)
            override fun shouldFollowInternalLink(
                link: org.readium.r2.shared.publication.Link,
                context: org.readium.r2.navigator.HyperlinkNavigator.LinkContext?,
            ): Boolean = true

            @OptIn(ExperimentalReadiumApi::class)
            override fun onExternalLinkActivated(url: org.readium.r2.shared.util.AbsoluteUrl) {
                // Fully offline app — no browser integration, same as feature:reader's EpubReaderScreen.
            }
        }

        val selectionCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                scope.launch {
                    val sel = navigator?.currentSelection() ?: return@launch
                    pendingLocatorForHighlight = sel.locator.toJSON().toString()
                    pendingText = sel.locator.text?.highlight ?: ""
                    showColorPicker = true
                    // Finish after saving selection so the action mode state machine
                    // resets cleanly — returning false leaves it stuck and blocks
                    // subsequent selections (same fix EpubReaderScreen applies).
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

        val factory = EpubNavigatorFactory(publication).createFragmentFactory(
            initialLocator     = null,
            initialPreferences = EpubPreferences(),
            listener           = listener,
            configuration      = config,
        )

        fragmentManager.fragmentFactory = factory
        fragmentManager.commitNow(allowStateLoss = true) {
            replace<EpubNavigatorFragment>(containerId, tag = VAULT_EPUB_FRAGMENT_TAG)
        }

        val nav = fragmentManager.findFragmentByTag(VAULT_EPUB_FRAGMENT_TAG) as? EpubNavigatorFragment
        navigator = nav

        val dirNavAdapter = nav?.let { n -> DirectionalNavigationAdapter(navigator = n).also { n.addInputListener(it) } }
        val centerTapListener = object : ReadiumInputListener {
            override fun onTap(event: TapEvent): Boolean {
                currentOnCentreTap.value.invoke()
                return true
            }
            override fun onDrag(event: ReadiumDragEvent): Boolean = false
            override fun onKey(event: ReadiumKeyEvent): Boolean = false
        }
        nav?.addInputListener(centerTapListener)

        nav?.currentLocator
            ?.onEach { locator -> currentOnPositionChanged.value.invoke(locator.toJSON().toString()) }
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
    LaunchedEffect(pendingLocatorJson, navigator) {
        val json = pendingLocatorJson ?: return@LaunchedEffect
        val nav = navigator ?: return@LaunchedEffect
        val locator = runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()
        if (locator != null) nav.go(locator, animated = false)
        onPendingLocatorConsumed()
    }

    // ── Highlight decorations ────────────────────────────────────────────────
    LaunchedEffect(navigator, highlights) {
        val nav = navigator ?: return@LaunchedEffect
        val decorations = highlights.mapNotNull { h ->
            try {
                val locator = Locator.fromJSON(JSONObject(h.positionRef))
                if (locator == null) {
                    Log.w("VaultEpubReaderScreen", "highlight ${h.id}: Locator.fromJSON returned null; skipping")
                    return@mapNotNull null
                }
                Decoration(
                    id      = "h_${h.id}",
                    locator = locator,
                    style   = Decoration.Style.Highlight(tint = AndroidColor.parseColor(h.colorHex)),
                )
            } catch (e: IllegalArgumentException) {
                Log.w("VaultEpubReaderScreen", "highlight ${h.id}: bad color hex '${h.colorHex}': ${e.message}")
                null
            } catch (e: org.json.JSONException) {
                Log.w("VaultEpubReaderScreen", "highlight ${h.id}: bad locator JSON: ${e.message}")
                null
            }
        }
        nav.applyDecorations(decorations, DECORATION_GROUP_HIGHLIGHTS)
    }
}
