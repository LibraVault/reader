package xyz.libravault.feature.reader

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.core.ui.theme.LibravaultTheme
import xyz.libravault.feature.reader.components.BookmarksSheet
import xyz.libravault.feature.reader.components.ReaderSettingsSheet
import xyz.libravault.feature.reader.components.ReaderTopBar
import xyz.libravault.feature.reader.epub.EpubReaderScreen
import xyz.libravault.feature.reader.pdf.PdfReaderScreen

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
) {
    val state      by viewModel.uiState.collectAsState()
    val bookmarks  by viewModel.bookmarks.collectAsState()
    val highlights by viewModel.highlights.collectAsState()

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

                Scaffold(
                    topBar = {
                        AnimatedVisibility(
                            visible = state.showToolbar,
                            enter   = fadeIn() + slideInVertically { -it },
                            exit    = fadeOut() + slideOutVertically { -it },
                        ) {
                            ReaderTopBar(
                                title       = item.title,
                                isBookmarked = bookmarks.any {
                                    it.positionRef == state.progress?.positionCfi
                                        || it.positionRef == "page:${state.progress?.pageIndex}"
                                },
                                onBack      = onBack,
                                onBookmark  = {
                                    val ref = state.progress?.positionCfi
                                        ?: state.progress?.pageIndex?.let { "page:$it" }
                                        ?: return@ReaderTopBar
                                    viewModel.addBookmark(ref)
                                },
                                onSettings  = viewModel::showSettings,
                            )
                        }
                    },
                ) { innerPadding ->

                    Box(Modifier.fillMaxSize().padding(innerPadding)) {
                        when (item.format) {
                            MediaFormat.EPUB -> {
                                val activity = LocalContext.current as? FragmentActivity
                                if (activity != null) {
                                    EpubReaderScreen(
                                        fileUri          = uri,
                                        initialCfi       = state.progress?.positionCfi,
                                        settings         = state.settings,
                                        bookmarks        = bookmarks,
                                        highlights       = highlights,
                                        fragmentManager  = activity.supportFragmentManager,
                                        onPositionChanged = viewModel::onEpubPositionChanged,
                                        onCentreTap      = viewModel::onCentreTap,
                                        onAddHighlight   = viewModel::addHighlight,
                                    )
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
                }

                // ── Settings sheet ────────────────────────────────────────────
                if (state.showSettingsSheet) {
                    ReaderSettingsSheet(
                        settings           = state.settings,
                        onThemeChanged     = viewModel::onThemeChanged,
                        onFontSizeChanged  = viewModel::onFontSizeChanged,
                        onFontFamilyChanged = viewModel::onFontFamilyChanged,
                        onScrollModeChanged = viewModel::onScrollModeChanged,
                        onDismiss          = viewModel::hideSettings,
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
