package xyz.libravault.feature.reader

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import xyz.libravault.core.ui.components.GeneratedCover
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.core.ui.components.BookmarkAddedToast
import xyz.libravault.core.ui.theme.LibravaultTheme
import xyz.libravault.feature.player.service.PlaybackStateHolder
import xyz.libravault.feature.reader.components.BookmarksSheet
import xyz.libravault.feature.reader.components.ReaderSettingsSheet
import xyz.libravault.feature.reader.components.ReaderTopBar
import xyz.libravault.feature.reader.epub.EpubReaderScreen
import xyz.libravault.feature.reader.epub.EpubReaderViewModel
import xyz.libravault.feature.reader.pdf.PdfReaderScreen

// Height reserved at the bottom of the reader content for the bottom bars.
// The audiobook mini-player uses this constant so the
// native EPUB WebView / PDF renderer never occupies that region.
private val BOTTOM_BAR_HEIGHT = 64.dp

/**
 * Entry point for the reader feature.
 * Routes to [EpubReaderScreen] or [PdfReaderScreen] based on the item's format,
 * and wraps both in a shared [Scaffold] with an animated toolbar.
 *
 * The toolbar auto-hides on centre-tap; the mini-player bottom bar is an
 * independent overlay — it stays pinned at the exact screen-bottom position
 * used by the Library mini-player, regardless of toolbar visibility.
 */
@Composable
fun ReaderScreen(
    itemId: Long? = null,
    fileUri: android.net.Uri? = null,
    onBack: () -> Unit,
    onNowPlayingClick: ((Long) -> Unit)? = null,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state      by viewModel.uiState.collectAsState()
    val bookmarks  by viewModel.bookmarks.collectAsState()
    val highlights by viewModel.highlights.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()

    // Shared scroll-to-page channel between BookmarksSheet and PdfReaderScreen.
    val pendingPdfPage = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Int?>(null)
    }

    // Show audiobook mini player whenever an audiobook is loaded.
    // Independent of toolbar visibility — stays pinned at the bottom even when the
    // toolbar hides on centre-tap (same behaviour as the Library screen mini-player).
    val showMiniPlayer = nowPlaying.itemId != null

    // Wrap in the reading theme chosen by the user
    LibravaultTheme(readingTheme = state.settings.theme) {
        when {
            state.isLoading -> LoadingScreen()
            state.error != null -> ErrorScreen(state.error!!, onBack)
            state.item != null -> {
                val item = state.item!!
                val uri  = Uri.parse(item.filePath)

                val epubViewModel: EpubReaderViewModel = hiltViewModel()
                val currentLocatorJson by epubViewModel.currentLocatorJson.collectAsState()

                val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        if (showMiniPlayer) {
                            ReaderMiniPlayerBar(
                                nowPlaying       = nowPlaying,
                                onNowPlayingClick = {
                                    nowPlaying.itemId?.let { id ->
                                        onNowPlayingClick?.invoke(id)
                                    }
                                },
                                onPrevious    = viewModel::skipPreviousAudiobook,
                                onSeekBack    = viewModel::seekBackAudiobook,
                                onPlayPause   = viewModel::playPauseAudiobook,
                                onSeekForward = viewModel::seekForwardAudiobook,
                                onNext        = viewModel::skipNextAudiobook,
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(Modifier.fillMaxSize()) {
                        // Bookmark-added confirmation toast — anchored at the bottom of the
                        // top 22% of the parent so it sits below the toolbar but well above
                        // the reader's mid-page reading position.
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .fillMaxHeight(0.22f),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            BookmarkAddedToast(
                                visible   = state.lastAddedBookmarkId != null,
                                onEdit    = {
                                    viewModel.clearBookmarkToast()
                                    viewModel.showBookmarks()
                                },
                                onDismiss = viewModel::clearBookmarkToast,
                            )
                        }

                        // Bottom padding is CONSTANT so the EPUB WebView never resizes
                        // when bars appear or disappear. Scaffold bottomBar handles the
                        // correct screen position (same pattern as Library screen).
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(
                                    top    = innerPadding.calculateTopPadding(),
                                    bottom = BOTTOM_BAR_HEIGHT + navBarPadding,
                                )
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
                                    ErrorScreen("This format opens in the player.", onBack)
                                }
                            }
                        }

                        AnimatedVisibility(
                            visible  = state.showToolbar,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth(),
                            enter = fadeIn() + slideInVertically { -it },
                            exit  = fadeOut() + slideOutVertically { -it },
                        ) {
                            ReaderTopBar(
                                title             = item.title,
                                onBack            = onBack,
                                onFontDecrease    = viewModel::decreaseFontSize,
                                onFontIncrease    = viewModel::increaseFontSize,
                                showFontControls  = item.format != MediaFormat.PDF,
                                onAddBookmark     = {
                                    val ref: String? = when (item.format) {
                                        MediaFormat.PDF ->
                                            "page:${state.progress?.pageIndex ?: 0}"
                                        else ->
                                            state.progress?.positionCfi ?: currentLocatorJson
                                    }
                                    ref?.let { viewModel.addBookmark(it) }
                                },
                                onShowBookmarks = viewModel::showBookmarks,
                                onSettings      = viewModel::showSettings,
                            )
                        }
                    }
                }

                // ── Settings sheet ────────────────────────────────────────────
                if (state.showSettingsSheet) {
                    ReaderSettingsSheet(
                        settings             = state.settings,
                        showFontControls     = item.format != MediaFormat.PDF,
                        onThemeChanged       = viewModel::onThemeChanged,
                        onFontSizeChanged    = viewModel::onFontSizeChanged,
                        onFontFamilyChanged  = viewModel::onFontFamilyChanged,
                        onLineSpacingChanged = viewModel::onLineSpacingChanged,
                        onScrollModeChanged  = viewModel::onScrollModeChanged,
                        onDismiss            = viewModel::hideSettings,
                    )
                }

                // ── Bookmarks sheet ───────────────────────────────────────────
                if (state.showBookmarksSheet) {
                    BookmarksSheet(
                        bookmarks       = bookmarks,
                        onBookmarkClick = { bookmark ->
                            when {
                                bookmark.positionRef.startsWith("page:") -> {
                                    bookmark.positionRef
                                        .removePrefix("page:")
                                        .toIntOrNull()
                                        ?.let { pendingPdfPage.value = it }
                                    viewModel.hideBookmarks()
                                }
                                else -> {
                                    epubViewModel.goToLocatorJson(bookmark.positionRef)
                                    viewModel.hideBookmarks()
                                }
                            }
                        },
                        onBookmarkDelete = viewModel::removeBookmark,
                        onEditNote       = viewModel::updateBookmarkNote,
                        onDismiss        = viewModel::hideBookmarks,
                    )
                }
            }
        }
    }
}

// ── Reader mini player bar ────────────────────────────────────────────────────

@Composable
private fun ReaderMiniPlayerBar(
    nowPlaying: PlaybackStateHolder.State,
    onNowPlayingClick: () -> Unit,
    onPrevious: () -> Unit,
    onSeekBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekForward: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Cover art thumbnail — tapping opens full player
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(onClick = onNowPlayingClick),
                contentAlignment = Alignment.Center,
            ) {
                if (nowPlaying.coverArtPath != null) {
                    AsyncImage(
                        model = nowPlaying.coverArtPath,
                        contentDescription = nowPlaying.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    GeneratedCover(
                        title = nowPlaying.title,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            // Title + author — tapping also opens full player
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onNowPlayingClick),
            ) {
                Text(
                    text = nowPlaying.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = nowPlaying.author,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Controls
            IconButton(onClick = onPrevious, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onSeekBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.FastRewind, contentDescription = "Skip back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = if (nowPlaying.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (nowPlaying.isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
            }
            IconButton(onClick = onSeekForward, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.FastForward, contentDescription = "Skip forward",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onNext, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
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
