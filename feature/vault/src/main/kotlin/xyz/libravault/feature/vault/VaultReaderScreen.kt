package xyz.libravault.feature.vault

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Reads one vault file — EPUB ([VaultEpubReaderScreen]) or PDF
 * ([VaultPdfReaderScreen]), dispatched by [VaultReaderViewModel.state]. Tap
 * the centre of an EPUB page to toggle the top bar (matches the PDF/audio
 * screens' always-visible bar less, but preserves the immersive-reading
 * expectation `feature:reader`'s own EPUB screen sets — see
 * `VaultEpubReaderScreen`'s `onCentreTap`).
 *
 * Bookmarks work the same way on both formats: the add-bookmark action in
 * the top bar bookmarks [VaultReaderViewModel.currentPositionRef] (the last
 * position either renderer reported); tapping a bookmark in
 * [VaultBookmarksSheet] requests navigation via
 * [VaultReaderViewModel.navigateToBookmark], which this screen forwards to
 * whichever renderer is active.
 *
 * Reading settings ([VaultReaderSettingsSheet]) mostly only change anything
 * for EPUB — PDF pages here are pre-rendered bitmaps with no theme/font
 * hook — except `scrollMode`, which now switches [VaultPdfReaderScreen]
 * between continuous-scroll and paginated rendering, matching
 * `feature:reader`'s own `PdfReaderScreen`. The settings icon opens the same
 * sheet for both formats rather than special-casing PDF away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultReaderScreen(
    onBack: () -> Unit,
    viewModel: VaultReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val highlights by viewModel.highlights.collectAsState()
    val pendingNavigationRef by viewModel.pendingNavigationRef.collectAsState()
    var showToolbar by remember { mutableStateOf(true) }
    var showBookmarksSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    SecureScreenEffect(enabled = remember { VaultScreenSecurityPreference.isEnabled(context) })

    LaunchedEffect(state) {
        if (state is VaultReaderState.WrongScreen) onBack()
    }

    val pendingPdfPage = pendingNavigationRef?.takeIf { it.startsWith("page:") }
        ?.removePrefix("page:")?.toIntOrNull()
    val pendingEpubLocatorJson = pendingNavigationRef?.takeUnless { it.startsWith("page:") }

    Scaffold(
        topBar = {
            if (showToolbar) {
                TopAppBar(
                    title = { Text(titleFor(state)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.addBookmark() }) {
                            Icon(Icons.Default.BookmarkAdd, contentDescription = "Add bookmark")
                        }
                        IconButton(onClick = { showBookmarksSheet = true }) {
                            Icon(Icons.Default.Bookmark, contentDescription = "Bookmarks")
                        }
                        IconButton(onClick = { showSettingsSheet = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Reader settings")
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(if (showToolbar) padding else PaddingValues(0.dp))) {
            when (val s = state) {
                is VaultReaderState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                is VaultReaderState.Error -> Text(
                    "Could not open file: ${s.message}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
                is VaultReaderState.DrmProtected -> Text(
                    "This book is protected and can't be opened" +
                        (s.schemeName?.let { " (protected by $it)" } ?: ""),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
                is VaultReaderState.WrongScreen -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                is VaultReaderState.EpubReady -> {
                    val fragmentManager = activity?.supportFragmentManager
                    if (fragmentManager != null) {
                        VaultEpubReaderScreen(
                            publication              = s.publication,
                            fragmentManager           = fragmentManager,
                            settings                  = settings,
                            highlights                = highlights,
                            pendingLocatorJson        = pendingEpubLocatorJson,
                            onPendingLocatorConsumed  = viewModel::clearPendingNavigation,
                            onPositionChanged         = viewModel::onEpubPositionChanged,
                            onCentreTap               = { showToolbar = !showToolbar },
                            onAddHighlight            = viewModel::addHighlight,
                            modifier                  = Modifier.fillMaxSize(),
                        )
                    }
                }
                is VaultReaderState.PdfReady -> VaultPdfReaderScreen(
                    reader           = viewModel.pdfReader(),
                    settings         = settings,
                    modifier         = Modifier.fillMaxSize(),
                    onPageChanged    = viewModel::onPdfPageChanged,
                    scrollToPage     = pendingPdfPage,
                    onScrollConsumed = viewModel::clearPendingNavigation,
                )
            }
        }
    }

    if (showBookmarksSheet) {
        VaultBookmarksSheet(
            bookmarks       = bookmarks,
            onBookmarkClick = { bookmark ->
                viewModel.navigateToBookmark(bookmark.positionRef)
                showBookmarksSheet = false
            },
            onBookmarkDelete = viewModel::removeBookmark,
            onEditNote       = viewModel::updateBookmarkNote,
            onDismiss        = { showBookmarksSheet = false },
        )
    }

    if (showSettingsSheet) {
        VaultReaderSettingsSheet(
            settings             = settings,
            showFontControls     = state !is VaultReaderState.PdfReady,
            onThemeChanged       = viewModel::onThemeChanged,
            onFontSizeChanged    = viewModel::onFontSizeChanged,
            onFontFamilyChanged  = viewModel::onFontFamilyChanged,
            onLineSpacingChanged = viewModel::onLineSpacingChanged,
            onScrollModeChanged  = viewModel::onScrollModeChanged,
            onDismiss            = { showSettingsSheet = false },
        )
    }
}

private fun titleFor(state: VaultReaderState): String = when (state) {
    is VaultReaderState.EpubReady -> state.title
    is VaultReaderState.PdfReady -> state.title
    else -> "Vault"
}
