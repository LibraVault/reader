package xyz.libravault.feature.vault

import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
import org.readium.r2.shared.publication.Publication

private const val VAULT_EPUB_FRAGMENT_TAG = "vault_epub_navigator"

/**
 * Minimal Readium navigator host for vault EPUBs — paginated reading with
 * left/right tap-to-turn and centre-tap-to-toggle-toolbar, default
 * preferences. Deliberately smaller than `feature:reader`'s
 * `EpubNavigatorView`: no highlight decorations, no bookmark navigation, no
 * per-user `ReaderSettings` hot-reload, no text-selection color picker — see
 * the PR description for why those are out of scope here, and
 * [VaultReaderViewModel]'s doc comment for what's not persisted (nothing:
 * this is session-only reading).
 */
@Composable
fun VaultEpubReaderScreen(
    publication: Publication,
    fragmentManager: FragmentManager,
    onCentreTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerId = remember { View.generateViewId() }
    val currentOnCentreTap = rememberUpdatedState(onCentreTap)
    val scope = rememberCoroutineScope()

    AndroidView(
        factory = { context -> FragmentContainerView(context).apply { id = containerId } },
        modifier = modifier.fillMaxSize(),
    )

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

        val config = EpubNavigatorFragment.Configuration().apply {
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

        // Consumed only to keep the flow alive for the fragment's lifetime —
        // vault reading doesn't persist position (see class doc).
        nav?.currentLocator?.onEach { }?.launchIn(scope)

        onDispose {
            dirNavAdapter?.let { nav?.removeInputListener(it) }
            nav?.removeInputListener(centerTapListener)
            if (!fragmentManager.isStateSaved) {
                fragmentManager.commitNow(allowStateLoss = true) {
                    nav?.let { remove(it) }
                }
            }
        }
    }
}
