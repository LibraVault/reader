package xyz.libravault.feature.player

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import xyz.libravault.core.ui.components.BookmarkAddedToast
import xyz.libravault.core.ui.components.CoverFormatBadge
import xyz.libravault.core.ui.components.GeneratedCover
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import xyz.libravault.core.ui.SecureScreenEffect
import xyz.libravault.core.ui.rememberScreenSecurityEnabled
import xyz.libravault.feature.player.components.BookmarksSheet
import xyz.libravault.feature.player.components.ChapterListSheet
import xyz.libravault.feature.player.components.PlaybackControls
import xyz.libravault.feature.player.components.PlayerSeekBar
import xyz.libravault.feature.player.components.SleepTimerSheet
import xyz.libravault.feature.player.components.SpeedPickerSheet
import xyz.libravault.feature.player.service.SleepTimerState
import xyz.libravault.core.domain.model.formatPlaybackSpeed
import xyz.libravault.core.domain.model.LibraryItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    itemId: Long? = null,
    fileUri: android.net.Uri? = null,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state     by viewModel.uiState.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    var showChapters    by remember { mutableStateOf(false) }
    var showSpeedPicker by remember { mutableStateOf(false) }

    // #493 — same FLAG_SECURE gating the deleted VaultPlayerScreen applied directly.
    // rememberScreenSecurityEnabled (not a one-shot remember{}) — same live-observing
    // pattern #571 already fixed VaultContentsScreen onto, called unconditionally each
    // recomposition so short-circuiting on isVaultItem doesn't skip the composable call.
    val screenSecurityEnabled = rememberScreenSecurityEnabled(LocalContext.current)
    SecureScreenEffect(enabled = state.isVaultItem && screenSecurityEnabled)

    // #526, ported from the deleted VaultPlayerScreen — re-check lock state every time
    // this screen comes back to the foreground. A no-op for a non-vault item
    // (PlayerViewModel.checkStillUnlocked() early-returns when vaultRef is null).
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentViewModel = rememberUpdatedState(viewModel)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) currentViewModel.value.checkStillUnlocked()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.wasLocked) {
        if (state.wasLocked) onBack()
    }

    Scaffold(
            topBar = {
                TopAppBar(
                    title = { },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        // Sleep timer
                        IconButton(onClick = viewModel::showSleepTimer) {
                            Icon(
                                imageVector = Icons.Default.Bedtime,
                                contentDescription = "Sleep timer",
                                tint = if (state.sleepTimerState !is SleepTimerState.Inactive)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        // Bookmarks
                        IconButton(onClick = viewModel::addBookmark) {
                            Icon(Icons.Default.BookmarkAdd, "Add bookmark")
                        }
                        IconButton(onClick = viewModel::showBookmarks) {
                            Icon(Icons.Default.Bookmark, "Bookmarks")
                        }
                        // Chapter list — enabled only when chapters are loaded
                        IconButton(
                            onClick  = { if (state.chapters.isNotEmpty()) showChapters = true },
                            enabled  = state.chapters.isNotEmpty(),
                        ) {
                            Icon(Icons.Default.FormatListNumbered, "Chapters")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
        ) { innerPadding ->

            Box(modifier = Modifier.fillMaxSize()) {
                // Bookmark-added confirmation toast, anchored at the bottom of the
                // top 22% of the player so it sits between the toolbar and the cover art.
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

            when {
                state.isLoading -> Box(
                    Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                state.error != null -> Column(
                    Modifier.fillMaxSize().padding(innerPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        state.error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = viewModel::retryPlayback) {
                        Text("Retry")
                    }
                }

                state.item != null -> {
                    val item = state.item!!
                    val isLandscape = isLandscapeOrientation(LocalConfiguration.current)
                    val actions = PlayerActions(
                        onSeek             = viewModel::seekTo,
                        onPlayPause        = viewModel::togglePlayPause,
                        onSkipBack         = viewModel::skipBack,
                        onSkipForward      = viewModel::skipForward,
                        onPreviousChapter  = viewModel::previousChapter,
                        onNextChapter      = viewModel::nextChapter,
                        onSpeedClick       = { showSpeedPicker = true },
                    )

                    if (isLandscape) {
                        LandscapePlayerContent(
                            item     = item,
                            state    = state,
                            actions  = actions,
                            modifier = Modifier.fillMaxSize().padding(innerPadding),
                        )
                    } else {
                        PortraitPlayerContent(
                            item     = item,
                            state    = state,
                            actions  = actions,
                            modifier = Modifier.fillMaxSize().padding(innerPadding),
                        )
                    }
                }
            }
            }  // end Box wrapping content
        }

        // ── Bottom sheets ─────────────────────────────────────────────────────
        if (state.showSleepTimerSheet) {
            SleepTimerSheet(
                timerState       = state.sleepTimerState,
                onSetTimer       = viewModel::startSleepTimer,
                onSetEndOfChapter = viewModel::startSleepTimerEndOfChapter,
                onCancel         = viewModel::cancelSleepTimer,
                onDismiss        = viewModel::hideSleepTimer,
            )
        }

        if (state.showBookmarksSheet) {
            BookmarksSheet(
                bookmarks        = bookmarks,
                onBookmarkClick  = viewModel::seekToBookmark,
                onBookmarkDelete = viewModel::removeBookmark,
                onEditNote       = viewModel::updateBookmarkNote,
                onDismiss        = viewModel::hideBookmarks,
            )
        }

        if (showSpeedPicker) {
            SpeedPickerSheet(
                currentSpeed    = state.playbackSpeed,
                onSpeedSelected = viewModel::setSpeed,
                onDismiss       = { showSpeedPicker = false },
            )
        }

        if (showChapters && state.chapters.isNotEmpty()) {
            ChapterListSheet(
                chapters            = state.chapters,
                currentChapterIndex = state.currentChapterIndex,
                onChapterSelected   = viewModel::goToChapter,
                onDismiss           = { showChapters = false },
            )
    }
}

// Split out of the isLandscape val above as a plain (non-Composable) function so
// the actual routing condition — not just the two layouts it chooses between —
// has direct test coverage (see PlayerOrientationTest). Inlining the comparison
// at the call site left it exercised only by manual QA: flip `==` to `!=` or
// swap the constant and every device would render the wrong layout for its
// rotation while CI stayed green, since PlayerScreenLandscapeTest calls
// LandscapePlayerContent/PortraitPlayerContent directly and never routes through
// this check.
internal fun isLandscapeOrientation(configuration: Configuration): Boolean =
    configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

// Bundles the player's callbacks so the layout composables below are pure
// functions of (item, state, actions) — no PlayerViewModel dependency — and can
// be exercised directly in a Robolectric Compose test the same way
// TtsSettingsSection is (see PlayerScreenLandscapeTest).
internal data class PlayerActions(
    val onSeek: (Long) -> Unit,
    val onPlayPause: () -> Unit,
    val onSkipBack: () -> Unit,
    val onSkipForward: () -> Unit,
    val onPreviousChapter: () -> Unit,
    val onNextChapter: () -> Unit,
    val onSpeedClick: () -> Unit,
)

// ── Portrait layout — single scrolling column, as before ───────────────────────
@Composable
internal fun PortraitPlayerContent(
    item: LibraryItem,
    state: PlayerUiState,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        PlayerCoverArt(
            item     = item,
            modifier = Modifier.fillMaxWidth(0.7f).aspectRatio(1f),
        )
        PlayerTitleBlock(item = item, state = state, horizontalAlignment = Alignment.CenterHorizontally)
        PlayerSeekBar(
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            bufferedMs = state.bufferedMs,
            onSeek     = actions.onSeek,
        )
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PlayerControlsRow(state = state, actions = actions)
        }
        PlayerSpeedButton(state = state, onClick = actions.onSpeedClick)
        PlayerSleepTimerStatus(state = state)
    }
}

// ── Landscape layout — cover/title on the left, transport on the right ─────────
// Rotating mid-playback used to be blocked entirely (see PlayerScreen's git
// history for the removed LockScreenOrientation() call); now the player rotates
// freely, so it needs a layout that looks intentional in landscape instead of
// just squeezing the portrait column sideways into a tall, narrow space.
@Composable
internal fun LandscapePlayerContent(
    item: LibraryItem,
    state: PlayerUiState,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            PlayerCoverArt(
                item     = item,
                modifier = Modifier.fillMaxHeight(0.65f).aspectRatio(1f),
            )
            Spacer(Modifier.height(12.dp))
            PlayerTitleBlock(item = item, state = state, horizontalAlignment = Alignment.CenterHorizontally)
        }

        Spacer(Modifier.width(24.dp))

        // No fillMaxHeight() here (unlike the cover/title Column above) — this
        // Column's height is just its intrinsic content height, so it has no
        // extra space to distribute and verticalArrangement = Center would be a
        // no-op. It reads centered anyway because the parent Row's own
        // verticalAlignment = CenterVertically centers the whole block.
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PlayerSeekBar(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                bufferedMs = state.bufferedMs,
                onSeek     = actions.onSeek,
            )
            Spacer(Modifier.height(8.dp))
            PlayerControlsRow(state = state, actions = actions)
            Spacer(Modifier.height(8.dp))
            PlayerSpeedButton(state = state, onClick = actions.onSpeedClick)
            PlayerSleepTimerStatus(state = state)
        }
    }
}

// ── Shared pieces used by both layouts ──────────────────────────────────────────

@Composable
private fun PlayerCoverArt(item: LibraryItem, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.clip(MaterialTheme.shapes.large),
        contentAlignment = Alignment.Center,
    ) {
        if (item.coverArtPath != null) {
            AsyncImage(
                model = item.coverArtPath,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            GeneratedCover(
                title = item.title,
                modifier = Modifier.fillMaxSize(),
                initialStyle = MaterialTheme.typography.displayLarge,
                format = CoverFormatBadge.fromFormatName(item.format.name),
            )
        }
    }
}

@Composable
private fun PlayerTitleBlock(
    item: LibraryItem,
    state: PlayerUiState,
    horizontalAlignment: Alignment.Horizontal,
) {
    Column(horizontalAlignment = horizontalAlignment) {
        Text(
            text      = item.title,
            style     = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines  = 2,
            overflow  = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text  = item.author,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        // Chapter name
        if (state.chapters.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text  = state.chapters
                    .getOrNull(state.currentChapterIndex)?.title ?: "",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PlayerControlsRow(state: PlayerUiState, actions: PlayerActions) {
    PlaybackControls(
        isPlaying          = state.isPlaying,
        hasPreviousChapter = state.currentChapterIndex > 0,
        hasNextChapter     = state.currentChapterIndex < state.chapters.size - 1,
        onPlayPause        = actions.onPlayPause,
        onSkipBack         = actions.onSkipBack,
        onSkipForward      = actions.onSkipForward,
        onPreviousChapter  = actions.onPreviousChapter,
        onNextChapter      = actions.onNextChapter,
    )
}

@Composable
private fun PlayerSpeedButton(state: PlayerUiState, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text  = formatPlaybackSpeed(state.playbackSpeed),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun PlayerSleepTimerStatus(state: PlayerUiState) {
    if (state.sleepTimerState is SleepTimerState.Active) {
        val remaining = (state.sleepTimerState as SleepTimerState.Active).remainingMs
        val min = remaining / 60_000
        val sec = (remaining % 60_000) / 1000
        Text(
            text  = "Sleep in %d:%02d".format(min, sec),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
