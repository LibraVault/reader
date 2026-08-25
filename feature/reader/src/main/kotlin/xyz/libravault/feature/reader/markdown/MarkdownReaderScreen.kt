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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import xyz.libravault.core.domain.model.ContentSource
import xyz.libravault.feature.reader.ReaderSettings
import xyz.libravault.feature.reader.markdown.mermaid.rememberMermaidMarkdownComponents
import xyz.libravault.feature.reader.markdown.toc.MarkdownTocExtractor
import xyz.libravault.feature.reader.markdown.toc.TocEntry
import kotlin.math.roundToInt

/**
 * Markdown viewer — v1: renders CommonMark via [com.mikepenz.markdown.m3.Markdown],
 * the Compose-native renderer. Relative image references (`![](./img.png)`) resolve
 * via [CoilMarkdownImageTransformer] against the file's vault-relative location (see
 * [xyz.libravault.core.storage.MarkdownAssetResolver]); remote http(s) URLs are never
 * loaded — LibraVault is offline-first. Typography (font family/size/line-spacing) is
 * adapted from [ReaderSettings] via [rememberMarkdownTypography]; reading-theme colors
 * (light/dark/sepia) already flow through automatically from
 * [xyz.libravault.core.ui.theme.LibravaultTheme]'s `MaterialTheme.colorScheme`, which
 * wraps this whole screen in ReaderScreen.kt — no separate color adapter needed. GFM
 * tables render natively as of the 0.32.0 renderer pin (see
 * feature/reader/build.gradle.kts for why 0.32.0 specifically, not the latest
 * release); iOS gained equivalent real table rendering (`MarkdownDocumentParser.swift`'s
 * `.table` block) in the same change.
 *
 * Renders one [com.mikepenz.markdown.m3.Markdown] call per [MarkdownTocExtractor]
 * section (rather than one call for the whole document) so each heading's on-screen
 * position can be recorded via [onGloballyPositioned] — the version of this renderer
 * pinned above predates its own `LazyColumn`/list-state API (added in 0.33.0), so this
 * is how TOC scroll-to-heading is done without it. Scroll position itself is still a
 * single shared [rememberScrollState] across all sections, so persistence (a 0.0..1.0
 * fraction — see #125/MIGRATION_6_7) is unaffected by this internal split. 0.33.0's
 * async `Markdown(String)` parsing would also change the timing this onGloballyPositioned
 * capture depends on, which is the other reason that bump is deliberately deferred (see
 * the build.gradle.kts comment) rather than folded into the GFM-table upgrade.
 *
 * Progress and bookmark navigation both restore by *section*, not by raw pixel — a
 * saved fraction is converted to the nearest section index (`fraction * sections.size`,
 * same conversion iOS's MarkdownReaderContent does against block count) and scrolled to
 * that section's recorded [onGloballyPositioned] offset once layout has produced one.
 * This is why both need to wait for `sectionOffsets` rather than scrolling immediately,
 * unlike the old pixel-offset version — a fraction can't become a pixel position before
 * the document's total scrollable height is known, which only happens after layout.
 *
 * @param contentSource        A real file or a vault entry (#505).
 * @param initialScrollFraction Restored scroll position (0.0..1.0 fraction through the
 *                             document) from Room, or null if never opened.
 * @param onScrollChanged      Reports scroll position changes (as a fraction) for Room
 *                             persistence.
 * @param onCentreTap          Tap in the centre-third of the screen — toggles the toolbar.
 * @param scrollToFraction     One-shot scroll target (fraction) set when the user taps a
 *                             bookmark.
 * @param onScrollConsumed     Called once [scrollToFraction] has been applied, mirroring
 *                             PdfReaderScreen's scrollToPage/onScrollConsumed pair.
 * @param onTocExtracted       Reports the document's headings once parsed, for the TOC sheet.
 * @param scrollToSectionIndex One-shot scroll target set when the user taps a TOC entry —
 *                             a [TocEntry.sectionIndex] directly, not a fraction.
 * @param onSectionScrollConsumed Called once [scrollToSectionIndex] has been applied.
 * @param vaultTreeUri         The item's vault folder SAF tree URI, for resolving relative
 *                             image references (see MarkdownAssetResolver) — null if the
 *                             item has no vault association (opened via external intent).
 */
@Composable
fun MarkdownReaderScreen(
    contentSource: ContentSource,
    initialScrollFraction: Double?,
    settings: ReaderSettings,
    onScrollChanged: (Double) -> Unit,
    onCentreTap: () -> Unit,
    scrollToFraction: Double? = null,
    onScrollConsumed: () -> Unit = {},
    onTocExtracted: (List<TocEntry>) -> Unit = {},
    scrollToSectionIndex: Int? = null,
    onSectionScrollConsumed: () -> Unit = {},
    vaultTreeUri: Uri? = null,
    viewModel: MarkdownReaderViewModel = hiltViewModel(),
) {
    LaunchedEffect(contentSource, vaultTreeUri) { viewModel.load(contentSource, vaultTreeUri) }
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
            val mermaidComponents = rememberMermaidMarkdownComponents(settings.theme)
            val imageTransformer = remember(current.assetParentDirectory) {
                CoilMarkdownImageTransformer(current.assetParentDirectory)
            }
            val sections = remember(current.text) { MarkdownTocExtractor.extractSections(current.text) }
            LaunchedEffect(sections) {
                onTocExtracted(sections.mapNotNull { it.heading })
            }

            // Always starts at the top — a saved fraction can't seed a pixel position
            // here directly, since the total scrollable height isn't known until layout
            // (see the class doc). Restored below, once sectionOffsets has what it needs.
            val scrollState = rememberScrollState(initial = 0)
            // Recorded lazily as each section lays out — see the class doc for why
            // this per-section split exists instead of one Markdown(content) call.
            val sectionOffsets = remember(sections) { mutableStateMapOf<Int, Int>() }

            LaunchedEffect(scrollState) {
                snapshotFlow { scrollState.value }
                    .distinctUntilChanged()
                    .collect {
                        if (scrollState.maxValue > 0) {
                            val fraction = scrollState.value.toDouble() / scrollState.maxValue.toDouble()
                            onScrollChanged(fraction.coerceIn(0.0, 1.0))
                        }
                    }
            }

            // Restore saved progress once, the first time the target section's offset
            // becomes available — re-keyed on that specific value (not e.g. sectionOffsets
            // as a whole) so this doesn't re-fire and yank the reader back to the restore
            // point after later relayouts (font-size/theme change, rotation) once the user
            // has since scrolled elsewhere themselves.
            var hasRestoredInitialPosition by remember(sections) { mutableStateOf(false) }
            val restoreTargetIndex = remember(sections, initialScrollFraction) {
                // fraction <= 0.0 is treated as "nothing to restore" here specifically
                // (skip animating to a position that's already the default), which is
                // why this doesn't just call sectionIndexForFraction directly — that
                // function itself treats a fraction of exactly 0.0 as section 0, a valid
                // target, matching what the bookmark-tap path below needs (a bookmark
                // saved at the very top of the document is still a real navigation target).
                initialScrollFraction
                    ?.takeIf { it > 0.0 }
                    ?.let { sectionIndexForFraction(it, sections.size) }
            }
            LaunchedEffect(restoreTargetIndex, restoreTargetIndex?.let { sectionOffsets[it] }) {
                if (hasRestoredInitialPosition) return@LaunchedEffect
                if (restoreTargetIndex == null) {
                    hasRestoredInitialPosition = true
                    return@LaunchedEffect
                }
                val offset = sectionOffsets[restoreTargetIndex] ?: return@LaunchedEffect
                scrollState.scrollTo(offset.coerceIn(0, scrollState.maxValue))
                hasRestoredInitialPosition = true
            }

            // Bookmark navigation: same fraction-to-nearest-section conversion as the
            // initial restore above, then animate there and clear the request. By the
            // time a user can tap a bookmark the document is already on screen (the
            // bookmarks sheet lives in the reader's own toolbar), so — like TOC
            // navigation just below — this assumes sectionOffsets is already populated
            // rather than waiting for it.
            LaunchedEffect(scrollToFraction) {
                val fraction = scrollToFraction ?: return@LaunchedEffect
                val targetIndex = sectionIndexForFraction(fraction, sections.size)
                val offset = targetIndex?.let { sectionOffsets[it] }
                if (offset != null) {
                    scrollState.animateScrollTo(offset.coerceIn(0, scrollState.maxValue))
                    onScrollConsumed()
                }
            }

            // TOC navigation: animate to the requested section's recorded offset. By
            // the time a user can tap a TOC entry the document is already fully laid
            // out (the TOC button lives in the reader's own toolbar), so the offset
            // is expected to already be recorded — same one-shot shape as scrollToFraction.
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
                            Markdown(
                                content = section.text,
                                typography = typography,
                                imageTransformer = imageTransformer,
                                components = mermaidComponents,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Converts a 0.0..1.0 scroll-position fraction into the nearest [MarkdownTocExtractor]
 * section index — the shared conversion both the initial-progress restore and
 * bookmark-tap navigation in [MarkdownReaderScreen] scroll to, since a fraction alone
 * (unlike a raw pixel offset) can't become a scroll target without first knowing how
 * many structural units the document has. Mirrors iOS's identical
 * `fraction * blocks.count` conversion in MarkdownReaderContent.swift, just against
 * TOC sections instead of parser blocks.
 *
 * Null for a null [fraction] or an empty document (nothing to target); otherwise
 * clamped to a valid index — `roundToInt()` alone can land one past the last section
 * for a fraction very close to 1.0 (e.g. 0.999999 against 3 sections rounds to index 3,
 * which doesn't exist for a 0..2 range), and a negative or >1.0 fraction should never
 * be silently ignored rather than clamped to the nearest real section, given it's
 * exactly what a pre-migration Markdown bookmark's raw pixel value (now
 * misinterpreted as a wildly out-of-range fraction) would look like before
 * MIGRATION_6_7's reset ran — clamping here is a second line of defense, not a
 * substitute for that reset.
 */
internal fun sectionIndexForFraction(fraction: Double?, sectionCount: Int): Int? {
    if (fraction == null || sectionCount <= 0) return null
    return (fraction * sectionCount).roundToInt().coerceIn(0, sectionCount - 1)
}
