package xyz.libravault.feature.reader.markdown

import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.flow.distinctUntilChanged
import xyz.libravault.feature.reader.ReaderSettings
import xyz.libravault.feature.reader.markdown.toc.MarkdownTocExtractor
import xyz.libravault.feature.reader.markdown.toc.TocEntry
import kotlin.math.roundToInt

/**
 * Markdown viewer — v1: renders CommonMark via [com.mikepenz.markdown.m3.Markdown],
 * the Compose-native renderer. No relative-image resolution yet (phase 5). Typography
 * (font family/size/line-spacing) is adapted from [ReaderSettings] via
 * [rememberMarkdownTypography]; reading-theme colors (light/dark/sepia) already flow
 * through automatically from [xyz.libravault.core.ui.theme.LibravaultTheme]'s
 * `MaterialTheme.colorScheme`, which wraps this whole screen in ReaderScreen.kt — no
 * separate color adapter needed. GFM tables are not yet supported — the renderer is
 * pinned to a pre-0.30.0 release for Kotlin 2.0.0 compatibility (see
 * feature/reader/build.gradle.kts); same fast-follow gap as the iOS viewer.
 *
 * Renders one [com.mikepenz.markdown.m3.Markdown] call per [MarkdownTocExtractor]
 * section (rather than one call for the whole document) so each heading's on-screen
 * position can be recorded via [onGloballyPositioned] — the version of this renderer
 * pinned above predates its own `LazyColumn`/list-state API (added in 0.33.0), so this
 * is how TOC scroll-to-heading is done without it. Scroll position itself is still a
 * single shared [rememberScrollState] across all sections, so persistence (a plain
 * pixel offset) is unaffected by this internal split.
 *
 * @param fileUri              SAF content URI of the Markdown file.
 * @param initialScrollOffset  Restored scroll offset (px) from Room, or null if never opened.
 * @param onScrollChanged      Reports scroll position changes for Room persistence.
 * @param onCentreTap          Tap in the centre-third of the screen — toggles the toolbar.
 * @param scrollToOffset       One-shot scroll target set when the user taps a bookmark.
 * @param onScrollConsumed     Called once [scrollToOffset] has been applied, mirroring
 *                             PdfReaderScreen's scrollToPage/onScrollConsumed pair.
 * @param onTocExtracted       Reports the document's headings once parsed, for the TOC sheet.
 * @param scrollToSectionIndex One-shot scroll target set when the user taps a TOC entry —
 *                             a [TocEntry.sectionIndex], not a raw pixel offset.
 * @param onSectionScrollConsumed Called once [scrollToSectionIndex] has been applied.
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
    onTocExtracted: (List<TocEntry>) -> Unit = {},
    scrollToSectionIndex: Int? = null,
    onSectionScrollConsumed: () -> Unit = {},
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
            val typography = rememberMarkdownTypography(settings)
            val sections = remember(current.text) { MarkdownTocExtractor.extractSections(current.text) }
            LaunchedEffect(sections) {
                onTocExtracted(sections.mapNotNull { it.heading })
            }

            val scrollState = rememberScrollState(initial = initialScrollOffset ?: 0)
            // Recorded lazily as each section lays out — see the class doc for why
            // this per-section split exists instead of one Markdown(content) call.
            val sectionOffsets = remember(sections) { mutableStateMapOf<Int, Int>() }

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

            // TOC navigation: animate to the requested section's recorded offset. By
            // the time a user can tap a TOC entry the document is already fully laid
            // out (the TOC button lives in the reader's own toolbar), so the offset
            // is expected to already be recorded — same one-shot shape as scrollToOffset.
            LaunchedEffect(scrollToSectionIndex) {
                val offset = scrollToSectionIndex?.let { sectionOffsets[it] }
                if (scrollToSectionIndex != null && offset != null) {
                    scrollState.animateScrollTo(offset.coerceIn(0, scrollState.maxValue))
                    onSectionScrollConsumed()
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(16.dp),
                ) {
                    sections.forEachIndexed { index, section ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { coordinates ->
                                    sectionOffsets[index] = coordinates.positionInParent().y.roundToInt()
                                },
                        ) {
                            Markdown(content = section.text, typography = typography)
                        }
                    }
                }
            }
        }
    }
}
