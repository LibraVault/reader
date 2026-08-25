package xyz.libravault.feature.reader

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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import xyz.libravault.core.ui.components.GeneratedCover
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import xyz.libravault.core.domain.model.ContentSource
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.core.tts.TtsStatus
import xyz.libravault.core.ui.SecureScreenEffect
import xyz.libravault.core.ui.rememberScreenSecurityEnabled
import xyz.libravault.core.ui.components.BookmarkAddedToast
import xyz.libravault.core.ui.components.WarmthOverlay
import xyz.libravault.core.ui.theme.LibravaultTheme
import xyz.libravault.feature.player.service.PlaybackStateHolder
import xyz.libravault.feature.reader.components.BookmarksSheet
import xyz.libravault.feature.reader.components.EpubTocSheet
import xyz.libravault.feature.reader.components.MarkdownTocSheet
import xyz.libravault.feature.reader.components.ReaderSettingsSheet
import xyz.libravault.feature.reader.components.ReaderTopBar
import xyz.libravault.feature.reader.epub.EpubReaderScreen
import xyz.libravault.feature.reader.epub.EpubReaderViewModel
import xyz.libravault.feature.reader.markdown.MarkdownReaderScreen
import xyz.libravault.feature.reader.markdown.MarkdownReaderViewModel
import xyz.libravault.feature.reader.markdown.toc.TocEntry
import xyz.libravault.feature.reader.pdf.PdfReaderScreen
import xyz.libravault.feature.reader.readaloud.ReadAloudPlayerScreen

// Height reserved at the bottom of the reader content for the bottom bars.
// The audiobook mini-player uses this constant so the
// native EPUB WebView / PDF renderer never occupies that region.
private val BOTTOM_BAR_HEIGHT = 64.dp

/** Which mini-bar (if any) [ReaderScreen]'s `bottomBar` should render. */
enum class ReaderBottomBar { NONE, AUDIOBOOK, READ_ALOUD }

/**
 * Picks which mini-bar wins when both an audiobook and a Read Aloud session look
 * "loaded" at the same time. [PlaybackStateHolder.State.isActive] is never cleared
 * once an audiobook (real-file or, since #493, vault-sourced) has been loaded
 * ([PlaybackStateHolder.clear] exists but is never called in production), so
 * `showMiniPlayer` alone stays true forever after the first audiobook play —
 * including while the audiobook is merely paused/backgrounded and a Read Aloud
 * session is the thing actually producing audio. Read Aloud must win whenever it's
 * active, or its mini-bar becomes unreachable and the stale audiobook bar's controls
 * end up silently stopping it instead.
 */
/**
 * Whether the audiobook mini-player should show — `isActive`, not `itemId != null`
 * (#493): `itemId` stays null by design for a vault-sourced item (see
 * [PlaybackStateHolder.State.vaultEntry]'s doc), which would otherwise leave the
 * mini-player never showing for vault audio. Extracted (matching
 * [selectReaderBottomBar]'s own precedent) so this one-line derivation has direct
 * test coverage instead of only being reachable through a full screen render.
 */
fun shouldShowAudiobookMiniPlayer(nowPlaying: PlaybackStateHolder.State): Boolean = nowPlaying.isActive

fun selectReaderBottomBar(showMiniPlayer: Boolean, showReadAloudBar: Boolean): ReaderBottomBar =
    when {
        showReadAloudBar -> ReaderBottomBar.READ_ALOUD
        showMiniPlayer   -> ReaderBottomBar.AUDIOBOOK
        else             -> ReaderBottomBar.NONE
    }

/**
 * Which formats expose the "Read Aloud" entry point in the settings sheet — EPUB
 * (#137) and Markdown (#276). Matches iOS's `ReaderSettingsSheet.showReadAloud`,
 * which already gates both formats. PDF Read Aloud is out of scope for both issues.
 */
fun readAloudSupported(format: MediaFormat): Boolean =
    format == MediaFormat.EPUB || format == MediaFormat.MARKDOWN

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
    // #493 — the full holder state, not just an itemId, so the caller can route to
    // either Screen.Player (real file) or Screen.VaultPlay (vaultEntry != null).
    onNowPlayingClick: ((PlaybackStateHolder.State) -> Unit)? = null,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state       by viewModel.uiState.collectAsState()
    val bookmarks   by viewModel.bookmarks.collectAsState()
    val highlights  by viewModel.highlights.collectAsState()
    val nowPlaying  by viewModel.nowPlaying.collectAsState()
    val readAloud   by viewModel.readAloudState.collectAsState()
    val readAloudPlayback by viewModel.readAloudPlayback.collectAsState()

    // #526 (ported here from the deleted VaultReaderScreen when #505 unified onto this
    // screen) — re-check lock state every time this screen comes back to the
    // foreground, same DisposableEffect+ON_RESUME idiom VaultListScreen/VaultPlayerScreen
    // already use, since nothing else here observes VaultSessionManager continuously. A
    // no-op for a non-vault contentSource (ReaderViewModel.checkStillUnlocked() early-
    // returns when vaultRef is null).
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentViewModel = rememberUpdatedState(viewModel)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) currentViewModel.value.checkStillUnlocked()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    androidx.compose.runtime.LaunchedEffect(state.wasLocked) {
        if (state.wasLocked) onBack()
    }

    // Shared scroll-to-page channel between BookmarksSheet and PdfReaderScreen.
    val pendingPdfPage = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Int?>(null)
    }
    // Shared scroll-to-fraction channel between BookmarksSheet and MarkdownReaderScreen.
    val pendingMarkdownScrollFraction = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Double?>(null)
    }
    // TOC for the currently-open Markdown file, and a one-shot scroll-to-section
    // channel between MarkdownTocSheet and MarkdownReaderScreen.
    val markdownToc = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<List<TocEntry>>(emptyList())
    }
    val pendingMarkdownSectionIndex = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Int?>(null)
    }

    // Show audiobook mini player whenever an audiobook is loaded.
    // Independent of toolbar visibility — stays pinned at the bottom even when the
    // toolbar hides on centre-tap (same behaviour as the Library screen mini-player).
    val showMiniPlayer = shouldShowAudiobookMiniPlayer(nowPlaying)

    // Read Aloud mini-bar (#137). This and showMiniPlayer are NOT mutually exclusive —
    // see selectReaderBottomBar's doc for why an audiobook can look "loaded" long after
    // it's actually relevant. selectReaderBottomBar() below resolves the precedence.
    val showReadAloudBar = readAloud.status == TtsStatus.PLAYING || readAloud.status == TtsStatus.PAUSED

    // Drives readAloudPlayback's elapsed/duration estimate (#138) — deliberately a
    // Compose-scoped LaunchedEffect rather than a delay() loop inside ReaderViewModel:
    // see ReaderViewModel.advanceReadAloudElapsed's doc for why a ViewModel-internal
    // ticker would leak past onCleared() in unit tests. showReadAloudBar as the key
    // means Compose cancels this effect the instant the bar disappears (session
    // stopped) and starts a fresh one when it reappears — same lifetime as the
    // mini-bar/Player screen, so `while (true)` below only ever runs while one of
    // those is actually showing.
    if (showReadAloudBar) {
        androidx.compose.runtime.LaunchedEffect(showReadAloudBar) {
            while (true) {
                kotlinx.coroutines.delay(1_000L)
                if (readAloud.status == TtsStatus.PLAYING) {
                    viewModel.advanceReadAloudElapsed((1_000L * readAloud.speechRate).toLong())
                }
            }
        }
    }

    // Wrap in the reading theme chosen by the user
    LibravaultTheme(readingTheme = state.settings.theme) {
        when {
            state.isLoading -> LoadingScreen()
            state.error != null -> ErrorScreen(state.error!!, onBack)
            state.contentSource != null -> {
                val contentSource = state.contentSource!!
                val format        = state.format!!

                // SecureScreenEffect's own doc comment (and VaultScreenSecurityPreference's)
                // already claimed ReaderScreen does this for a ContentSource.VaultEntry
                // (#505) — it never actually did. Real gap: decrypted vault EPUB/PDF/
                // Markdown content had no FLAG_SECURE protection. rememberScreenSecurityEnabled
                // (not a one-shot remember{}) — same live-observing pattern #571 already
                // fixed VaultContentsScreen onto, called unconditionally each recomposition
                // so short-circuiting on contentSource doesn't skip the composable call.
                val screenSecurityEnabled = rememberScreenSecurityEnabled(LocalContext.current)
                SecureScreenEffect(enabled = contentSource is ContentSource.VaultEntry && screenSecurityEnabled)

                val epubViewModel: EpubReaderViewModel = hiltViewModel()
                val currentLocatorJson by epubViewModel.currentLocatorJson.collectAsState()
                val epubToc by epubViewModel.tocEntries.collectAsState()
                // Sibling hiltViewModel(), same pattern as epubViewModel above — the
                // instance is shared with the one MarkdownReaderScreen would otherwise
                // create for itself (same ViewModelStoreOwner), so Read Aloud (#276) can
                // drive its chapter walk from here regardless of which composable first
                // triggered the ViewModel's creation.
                val markdownViewModel: MarkdownReaderViewModel = hiltViewModel()

                val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    // A real topBar (rather than a floating overlay drawn on top of the
                    // content) so Scaffold's innerPadding actually reserves space for it —
                    // previously the toolbar was an AnimatedVisibility overlay inside the
                    // content Box with no topBar set at all, so innerPadding.calculateTopPadding()
                    // was always 0 and page text scrolled up underneath the (translucent,
                    // alpha=0.95f) toolbar instead of stopping below it. AnimatedVisibility
                    // inside a Scaffold slot still animates its height, so the content
                    // correctly expands to fill the screen when the toolbar auto-hides.
                    topBar = {
                        AnimatedVisibility(
                            visible = state.showToolbar,
                            modifier = Modifier.fillMaxWidth(),
                            enter = fadeIn() + slideInVertically { -it },
                            exit  = fadeOut() + slideOutVertically { -it },
                        ) {
                            ReaderTopBar(
                                title             = state.title,
                                onBack            = onBack,
                                onFontDecrease    = viewModel::decreaseFontSize,
                                onFontIncrease    = viewModel::increaseFontSize,
                                showFontControls  = format != MediaFormat.PDF,
                                onAddBookmark     = {
                                    val ref: String? = when (format) {
                                        MediaFormat.PDF ->
                                            "page:${state.progress?.pageIndex ?: 0}"
                                        MediaFormat.MARKDOWN ->
                                            "scroll:${state.progress?.markdownScrollFraction ?: 0.0}"
                                        else ->
                                            state.progress?.positionCfi ?: currentLocatorJson
                                    }
                                    ref?.let { viewModel.addBookmark(it) }
                                },
                                onShowBookmarks = viewModel::showBookmarks,
                                onSettings      = viewModel::showSettings,
                                onShowToc       = if (format == MediaFormat.MARKDOWN || format == MediaFormat.EPUB) {
                                    viewModel::showToc
                                } else {
                                    null
                                },
                                // Read Aloud (#137/#276) — a prominent toolbar action rather
                                // than a row buried in the settings sheet. See
                                // ReaderTopBar.showReadAloud's doc for why.
                                showReadAloud    = readAloudSupported(format),
                                readAloudActive  = showReadAloudBar,
                                onReadAloudClick = {
                                    if (showReadAloudBar) {
                                        viewModel.stopReadAloud()
                                    } else {
                                        when (format) {
                                            MediaFormat.EPUB -> viewModel.startReadAloud(
                                                getInitialText  = epubViewModel::getChapterTextFromProgression,
                                                getNextText     = epubViewModel::getNextChapterText,
                                                getPreviousText = epubViewModel::getPreviousChapterText,
                                                chapterIndex    = { epubViewModel.ttsChapterIndex },
                                                chapterCount    = { epubViewModel.ttsChapterCount },
                                            )
                                            MediaFormat.MARKDOWN -> viewModel.startReadAloud(
                                                getInitialText = {
                                                    markdownViewModel.getChapterTextFromProgression(
                                                        state.progress?.markdownScrollFraction
                                                    )
                                                },
                                                getNextText     = markdownViewModel::getNextChapterText,
                                                getPreviousText = markdownViewModel::getPreviousChapterText,
                                                chapterIndex    = { markdownViewModel.ttsChapterIndex },
                                                chapterCount    = { markdownViewModel.ttsChapterCount },
                                            )
                                            else -> {}
                                        }
                                    }
                                },
                            )
                        }
                    },
                    bottomBar = {
                        when (selectReaderBottomBar(showMiniPlayer, showReadAloudBar)) {
                            ReaderBottomBar.AUDIOBOOK -> ReaderMiniPlayerBar(
                                nowPlaying       = nowPlaying,
                                onNowPlayingClick = { onNowPlayingClick?.invoke(nowPlaying) },
                                onPrevious    = viewModel::skipPreviousAudiobook,
                                onSeekBack    = viewModel::seekBackAudiobook,
                                onPlayPause   = viewModel::playPauseAudiobook,
                                onSeekForward = viewModel::seekForwardAudiobook,
                                onNext        = viewModel::skipNextAudiobook,
                            )
                            ReaderBottomBar.READ_ALOUD -> ReaderReadAloudMiniBar(
                                isPlaying   = readAloud.status == TtsStatus.PLAYING,
                                onExpand    = viewModel::showReadAloudPlayer,
                                onPlayPause = viewModel::toggleReadAloudPlayPause,
                                onStop      = viewModel::stopReadAloud,
                            )
                            ReaderBottomBar.NONE -> {}
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
                            when (format) {
                                MediaFormat.EPUB -> {
                                    val activity = LocalContext.current as? FragmentActivity
                                    if (activity != null) {
                                        EpubReaderScreen(
                                            contentSource     = contentSource,
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
                                        contentSource    = contentSource,
                                        initialPage      = state.progress?.pageIndex ?: 0,
                                        scrollToPage     = pendingPdfPage.value,
                                        onScrollConsumed = { pendingPdfPage.value = null },
                                        settings         = state.settings,
                                        onPageChanged    = viewModel::onPdfPageChanged,
                                        onCentreTap      = viewModel::onCentreTap,
                                    )
                                }

                                MediaFormat.MARKDOWN -> {
                                    MarkdownReaderScreen(
                                        contentSource    = contentSource,
                                        initialScrollFraction = state.progress?.markdownScrollFraction,
                                        scrollToFraction = pendingMarkdownScrollFraction.value,
                                        onScrollConsumed = { pendingMarkdownScrollFraction.value = null },
                                        settings         = state.settings,
                                        onScrollChanged  = viewModel::onMarkdownScrollChanged,
                                        onCentreTap      = viewModel::onCentreTap,
                                        onTocExtracted   = { markdownToc.value = it },
                                        scrollToSectionIndex   = pendingMarkdownSectionIndex.value,
                                        onSectionScrollConsumed = { pendingMarkdownSectionIndex.value = null },
                                        vaultTreeUri     = state.vaultTreeUri,
                                        viewModel        = markdownViewModel,
                                    )
                                }

                                else -> {
                                    ErrorScreen("This format opens in the player.", onBack)
                                }
                            }

                            // Warmth / blue-light overlay (#422) — screen-level tint drawn on
                            // top of whichever format just rendered above. Always present in
                            // the tree (renders nothing when warmth == 0f) rather than
                            // conditionally composed, per WarmthOverlay's own doc.
                            WarmthOverlay(warmth = state.settings.warmth, modifier = Modifier.fillMaxSize())
                        }
                    }
                }

                // ── TOC sheet (Markdown and EPUB — #596) ────────────────────────
                if (state.showTocSheet) {
                    when (format) {
                        MediaFormat.MARKDOWN -> MarkdownTocSheet(
                            entries      = markdownToc.value,
                            onEntryClick = { entry ->
                                pendingMarkdownSectionIndex.value = entry.sectionIndex
                                viewModel.hideToc()
                            },
                            onDismiss    = viewModel::hideToc,
                        )
                        MediaFormat.EPUB -> EpubTocSheet(
                            entries      = epubToc,
                            onEntryClick = { entry ->
                                epubViewModel.goToLocatorJson(entry.locatorJson)
                                viewModel.hideToc()
                            },
                            onDismiss    = viewModel::hideToc,
                        )
                        else -> {}
                    }
                }

                // ── Settings sheet ────────────────────────────────────────────
                if (state.showSettingsSheet) {
                    ReaderSettingsSheet(
                        settings             = state.settings,
                        showFontControls     = format != MediaFormat.PDF,
                        onThemeChanged       = viewModel::onThemeChanged,
                        onFontSizeChanged    = viewModel::onFontSizeChanged,
                        onFontFamilyChanged  = viewModel::onFontFamilyChanged,
                        onLineSpacingChanged = viewModel::onLineSpacingChanged,
                        onScrollModeChanged  = viewModel::onScrollModeChanged,
                        onWarmthChanged      = viewModel::onWarmthChanged,
                        onDismiss            = viewModel::hideSettings,
                        onAutoScrollEnabledChanged = viewModel::onAutoScrollEnabledChanged,
                        onAutoScrollSpeedChanged   = viewModel::onAutoScrollSpeedChanged,
                        // Margins/justification/hyphenation (#421) — EPUB only, see
                        // ReaderSettingsSheet.showEpubLayoutControls's doc.
                        showEpubLayoutControls = format == MediaFormat.EPUB,
                        onMarginScaleChanged   = viewModel::onMarginScaleChanged,
                        onJustifyTextChanged   = viewModel::onJustifyTextChanged,
                        onHyphenationChanged   = viewModel::onHyphenationChanged,
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
                                bookmark.positionRef.startsWith("scroll:") -> {
                                    bookmark.positionRef
                                        .removePrefix("scroll:")
                                        .toDoubleOrNull()
                                        ?.let { pendingMarkdownScrollFraction.value = it }
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

                // ── Read Aloud Player screen (#138) ───────────────────────────
                if (state.showReadAloudPlayer) {
                    ReadAloudPlayerScreen(
                        title               = state.title,
                        author              = state.author,
                        isPlaying           = readAloud.status == TtsStatus.PLAYING,
                        elapsedMs           = readAloudPlayback.elapsedMs,
                        durationMs          = readAloudPlayback.durationMs,
                        speed               = readAloud.speechRate,
                        chapterIndex        = readAloudPlayback.chapterIndex,
                        chapterCount        = readAloudPlayback.chapterCount,
                        sleepTimerState     = readAloudPlayback.sleepTimerState,
                        showSleepTimerSheet = state.showReadAloudSleepTimerSheet,
                        onBack              = viewModel::hideReadAloudPlayer,
                        onPlayPause         = viewModel::toggleReadAloudPlayPause,
                        onStop              = viewModel::stopReadAloud,
                        onSeek                      = viewModel::seekReadAloud,
                        onSkipBack                  = { viewModel.skipBackwardReadAloud() },
                        onSkipForward               = { viewModel.skipForwardReadAloud() },
                        onPreviousChapter           = viewModel::previousReadAloudChapter,
                        onNextChapter               = viewModel::nextReadAloudChapter,
                        onSpeedSelected             = viewModel::setReadAloudSpeed,
                        onShowSleepTimer            = viewModel::showReadAloudSleepTimer,
                        onHideSleepTimer            = viewModel::hideReadAloudSleepTimer,
                        onSetSleepTimer             = viewModel::startReadAloudSleepTimer,
                        onSetSleepTimerEndOfChapter = viewModel::startReadAloudSleepTimerEndOfChapter,
                        onCancelSleepTimer          = viewModel::cancelReadAloudSleepTimer,
                    )
                }
            }
        }
    }
}

// ── Reader mini player bar ────────────────────────────────────────────────────

// internal (was private) so ReaderMiniPlayerBarTest can render it directly —
// see docs/TEST_COVERAGE_PRD.md Phase 7. ReaderScreen's top-level composable
// itself is NOT extracted the PlayerScreen/SettingsScreen way: epubViewModel
// and markdownViewModel above are obtained via sibling hiltViewModel() calls
// specifically so their instances are shared with the ViewModelStoreOwner
// child screens (EpubReaderScreen/MarkdownReaderScreen) use — pulling that
// into a "pure" content composable risks silently breaking that sharing,
// which is exactly the "a real behaviour change slipping in unnoticed" risk
// Phase 7 already calls out. These two mini-bars are the genuinely pure,
// currently-untested pieces of this file; the top-level composable needs a
// deliberate design pass, not a mechanical extraction.
@Composable
internal fun ReaderMiniPlayerBar(
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

// ── Read Aloud mini bar (#137) ────────────────────────────────────────────────

/**
 * Mini-bar for an active EPUB/Markdown Read Aloud (TTS) session — visually and
 * interactionally consistent with [ReaderMiniPlayerBar]: same surface, elevation,
 * shape, icon sizing and position. Tapping the icon or title expands the full
 * Player screen (#138's [xyz.libravault.feature.reader.readaloud.ReadAloudPlayerScreen]),
 * the same way [ReaderMiniPlayerBar]'s cover art/title do for the audiobook player.
 */
@Composable
internal fun ReaderReadAloudMiniBar(
    isPlaying: Boolean,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
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
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(onClick = onExpand),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Headphones,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.width(10.dp))

            Text(
                text = "Reading aloud",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onExpand),
            )

            IconButton(onClick = onPlayPause, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause reading" else "Resume reading",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
            }
            IconButton(onClick = onStop, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Stop,
                    contentDescription = "Stop reading aloud",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
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
