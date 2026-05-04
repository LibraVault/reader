package xyz.libravault.feature.reader

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.core.tts.TtsStatus
import xyz.libravault.core.ui.theme.LibravaultTheme
import xyz.libravault.feature.reader.components.BookmarksSheet
import xyz.libravault.feature.reader.components.ReaderSettingsSheet
import xyz.libravault.feature.reader.components.ReaderTopBar
import xyz.libravault.feature.reader.components.TtsBottomBar
import xyz.libravault.feature.reader.components.TtsSettingsSheet
import xyz.libravault.feature.reader.epub.EpubReaderScreen
import xyz.libravault.feature.reader.epub.EpubReaderViewModel
import xyz.libravault.feature.reader.pdf.PdfReaderScreen
import xyz.libravault.feature.reader.tts.TtsViewModel

// Fixed height of the TTS bar. Pre-reserved in the reader content Box so the native
// WebView / PdfRenderer (AndroidView) never occupies that region — native Views are
// always drawn on top of the Compose canvas and would otherwise cover the bar.
private val TTS_BAR_HEIGHT = 64.dp

/**
 * Entry point for the reader feature.
 * Routes to [EpubReaderScreen] or [PdfReaderScreen] based on the item's format,
 * and wraps both in a shared [Scaffold] with an animated toolbar.
 *
 * The toolbar auto-hides when the user starts reading and reappears on
 * a centre-third tap (standard e-reader convention).
 */
@Composable
fun ReaderScreen(
    itemId: Long? = null,
    fileUri: android.net.Uri? = null,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
    ttsViewModel: TtsViewModel = hiltViewModel(),
) {
    val state      by viewModel.uiState.collectAsState()
    val bookmarks  by viewModel.bookmarks.collectAsState()
    val highlights by viewModel.highlights.collectAsState()
    val ttsState   by ttsViewModel.state.collectAsState()

    val scope = rememberCoroutineScope()

    // Shared scroll-to-page channel between BookmarksSheet and PdfReaderScreen.
    // null = no pending scroll; set by onBookmarkClick, cleared by onScrollConsumed.
    val pendingPdfPage = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Int?>(null)
    }

    // Wrap in the reading theme chosen by the user
    LibravaultTheme(readingTheme = state.settings.theme) {
        when {
            state.isLoading -> LoadingScreen()
            state.error != null -> ErrorScreen(state.error!!, onBack)
            state.item != null -> {
                val item = state.item!!
                val uri  = Uri.parse(item.filePath)

                // EpubReaderViewModel is scoped to this BackStackEntry — obtain it here so
                // we can call getChapterText() from TTS controls without threading through
                // multiple composable layers.
                val epubViewModel: EpubReaderViewModel = hiltViewModel()

                // Auto-advance TTS to the next chapter when the current one finishes.
                // Runs only while the TTS bar is visible; restarts if the item changes.
                LaunchedEffect(state.showTtsBar, item.id) {
                    if (!state.showTtsBar || item.format != MediaFormat.EPUB) return@LaunchedEffect
                    ttsViewModel.completionEvent.collect {
                        val nextText = epubViewModel.getNextChapterText() ?: return@collect
                        ttsViewModel.setContent(nextText)
                        ttsViewModel.play()
                    }
                }

                Scaffold(
                    topBar = {
                        AnimatedVisibility(
                            visible = state.showToolbar,
                            enter   = fadeIn() + slideInVertically { -it },
                            exit    = fadeOut() + slideOutVertically { -it },
                        ) {
                            ReaderTopBar(
                                title        = item.title,
                                isBookmarked = bookmarks.any {
                                    it.positionRef == state.progress?.positionCfi
                                        || it.positionRef == "page:${state.progress?.pageIndex}"
                                },
                                isTtsActive  = state.showTtsBar,
                                onBack       = onBack,
                                onBookmark   = {
                                    val ref = state.progress?.positionCfi
                                        ?: state.progress?.pageIndex?.let { "page:$it" }
                                        ?: return@ReaderTopBar
                                    viewModel.addBookmark(ref)
                                },
                                onSettings   = viewModel::showSettings,
                                onTts        = {
                                    viewModel.toggleTtsBar()
                                    ttsViewModel.initializeIfNeeded()
                                },
                            )
                        }
                    },
                    // TTS bar is NOT placed in Scaffold.bottomBar. Reason: AndroidView (the
                    // Readium WebView / PDF renderer) is a native Android View and is always
                    // drawn on top of the Compose canvas in the same screen region. If the bar
                    // lives in bottomBar, Scaffold reserves space but the native View still
                    // covers the Compose-drawn bar during the layout-update delay.
                    //
                    // Fix: put the bar as a Box overlay INSIDE the content area, and push the
                    // reader content up with explicit bottom padding so the native View never
                    // occupies the TTS bar region.
                ) { innerPadding ->

                    Box(Modifier.fillMaxSize().padding(innerPadding)) {

                        // Reader content — padded away from the TTS bar so the native View
                        // (WebView / PdfRenderer) never overlaps the Compose bar layer.
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(bottom = if (state.showTtsBar) TTS_BAR_HEIGHT else 0.dp)
                        ) {
                            when (item.format) {
                                MediaFormat.EPUB -> {
                                    val activity = LocalContext.current as? FragmentActivity
                                    if (activity != null) {
                                        EpubReaderScreen(
                                            fileUri           = uri,
                                            initialCfi        = state.progress?.positionCfi,
                                            settings          = state.settings,
                                            bookmarks         = bookmarks,
                                            highlights        = highlights,
                                            fragmentManager   = activity.supportFragmentManager,
                                            onPositionChanged = viewModel::onEpubPositionChanged,
                                            onCentreTap       = viewModel::onCentreTap,
                                            onAddHighlight    = viewModel::addHighlight,
                                            viewModel         = epubViewModel,
                                        )
                                    } else {
                                        ErrorScreen("EPUB reader requires a FragmentActivity context.", onBack)
                                    }
                                }

                                MediaFormat.PDF -> {
                                    PdfReaderScreen(
                                        fileUri          = uri,
                                        initialPage      = state.progress?.pageIndex ?: 0,
                                        scrollToPage     = pendingPdfPage.value,
                                        onScrollConsumed = { pendingPdfPage.value = null },
                                        settings         = state.settings,
                                        onPageChanged    = viewModel::onPdfPageChanged,
                                        onCentreTap      = viewModel::onCentreTap,
                                    )
                                }

                                else -> {
                                    // Audio formats — navigation handled in PlayerScreen (M3)
                                    ErrorScreen("This format opens in the player.", onBack)
                                }
                            }
                        }

                        // TTS bar — overlaid in the space left by the content padding above.
                        // Slide-in starts from the bottom edge of the content area and moves up,
                        // entirely within the native-View-free zone.
                        AnimatedVisibility(
                            visible  = state.showTtsBar,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth(),
                            enter = fadeIn() + slideInVertically { it },
                            exit  = fadeOut() + slideOutVertically { it },
                        ) {
                            TtsBottomBar(
                                state          = ttsState,
                                onPlay         = {
                                    if (ttsState.status != TtsStatus.PAUSED) {
                                        scope.launch {
                                            val text = if (item.format == MediaFormat.EPUB) {
                                                epubViewModel.getChapterText()
                                            } else null
                                            if (text != null) ttsViewModel.setContent(text)
                                            ttsViewModel.play()
                                        }
                                    } else {
                                        ttsViewModel.play()
                                    }
                                },
                                onPause        = ttsViewModel::pause,
                                onStop         = {
                                    ttsViewModel.stop()
                                    epubViewModel.resetTtsPosition()
                                },
                                onOpenSettings = viewModel::showTtsSheet,
                            )
                        }
                    }
                }

                // ── Settings sheet ────────────────────────────────────────────
                if (state.showSettingsSheet) {
                    ReaderSettingsSheet(
                        settings            = state.settings,
                        onThemeChanged      = viewModel::onThemeChanged,
                        onFontSizeChanged   = viewModel::onFontSizeChanged,
                        onFontFamilyChanged = viewModel::onFontFamilyChanged,
                        onScrollModeChanged = viewModel::onScrollModeChanged,
                        onDismiss           = viewModel::hideSettings,
                    )
                }

                // ── Bookmarks sheet ───────────────────────────────────────────
                if (state.showBookmarksSheet) {
                    BookmarksSheet(
                        bookmarks       = bookmarks,
                        onBookmarkClick = { bookmark ->
                            when {
                                // PDF bookmark — positionRef stored as "page:N"
                                bookmark.positionRef.startsWith("page:") -> {
                                    bookmark.positionRef
                                        .removePrefix("page:")
                                        .toIntOrNull()
                                        ?.let { pendingPdfPage.value = it }
                                    viewModel.hideBookmarks()
                                }
                                // EPUB — CFI navigation via Readium navigator;
                                // scroll-to-CFI to be wired once EpubNavigatorFragment
                                // exposes a stable goTo(Locator) API in beta.2+
                                else -> viewModel.hideBookmarks()
                            }
                        },
                        onBookmarkDelete = viewModel::removeBookmark,
                        onDismiss        = viewModel::hideBookmarks,
                    )
                }

                // ── TTS settings sheet ────────────────────────────────────────
                if (state.showTtsSheet) {
                    TtsSettingsSheet(
                        state               = ttsState,
                        onVoiceSelected     = ttsViewModel::setVoice,
                        onSpeechRateChanged = ttsViewModel::setSpeechRate,
                        onDismiss           = viewModel::hideTtsSheet,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator()
    }
}

@Composable
private fun ErrorScreen(message: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text  = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
