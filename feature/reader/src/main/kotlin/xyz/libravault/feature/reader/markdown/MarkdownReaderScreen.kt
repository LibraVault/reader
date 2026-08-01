package xyz.libravault.feature.reader.markdown

import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.flow.distinctUntilChanged
import xyz.libravault.feature.reader.ReaderSettings

/**
 * Markdown viewer — v1: renders CommonMark via [com.mikepenz.markdown.m3.Markdown],
 * the Compose-native renderer. No relative-image resolution or TOC yet (later
 * phases); theming is currently Compose's default Material3 styling rather than
 * [ReaderSettings] (Phase 4). GFM tables are not yet supported — the renderer is
 * pinned to a pre-0.30.0 release for Kotlin 2.0.0 compatibility (see
 * feature/reader/build.gradle.kts); same fast-follow gap as the iOS viewer.
 *
 * @param fileUri             SAF content URI of the Markdown file.
 * @param initialScrollOffset Restored scroll offset (px) from Room, or null if never opened.
 * @param onScrollChanged     Reports scroll position changes for Room persistence.
 * @param onCentreTap         Tap in the centre-third of the screen — toggles the toolbar.
 * @param scrollToOffset      One-shot scroll target set when the user taps a bookmark.
 * @param onScrollConsumed    Called once [scrollToOffset] has been applied, mirroring
 *                            PdfReaderScreen's scrollToPage/onScrollConsumed pair.
 */
@Composable
fun MarkdownReaderScreen(
    fileUri: Uri,
    initialScrollOffset: Int?,
    settings: ReaderSettings,
    onScrollChanged: (Int) -> Unit,
    onCentreTap: () -> Unit,
    scrollToOffset: Int? = null,
    onScrollConsumed: () -> Unit = {},
    viewModel: MarkdownReaderViewModel = hiltViewModel(),
) {
    LaunchedEffect(fileUri) { viewModel.load(fileUri) }
    val state by viewModel.state.collectAsState()

    when (val current = state) {
        is MarkdownPublicationState.Idle,
        is MarkdownPublicationState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        is MarkdownPublicationState.Error -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = current.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(32.dp),
                )
            }
        }

        is MarkdownPublicationState.Ready -> {
            val scrollState = rememberScrollState(initial = initialScrollOffset ?: 0)

            LaunchedEffect(scrollState) {
                snapshotFlow { scrollState.value }
                    .distinctUntilChanged()
                    .collect { onScrollChanged(it) }
            }

            // Bookmark navigation: animate to the requested offset then clear the request.
            LaunchedEffect(scrollToOffset) {
                if (scrollToOffset != null) {
                    scrollState.animateScrollTo(scrollToOffset.coerceIn(0, scrollState.maxValue))
                    onScrollConsumed()
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val xDp = offset.x / density
                            val width = size.width / density
                            if (xDp in (width * 0.33f)..(width * 0.67f)) onCentreTap()
                        }
                    },
            ) {
                Markdown(
                    content = current.text,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(16.dp),
                )
            }
        }
    }
}
