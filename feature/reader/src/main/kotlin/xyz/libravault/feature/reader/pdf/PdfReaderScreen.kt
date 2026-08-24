package xyz.libravault.feature.reader.pdf

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.libravault.feature.reader.ScrollMode
import xyz.libravault.feature.reader.ReaderSettings
import xyz.libravault.feature.reader.autoAdvancePages
import xyz.libravault.feature.reader.autoScroll

/**
 * PDF viewer using Android's native [PdfRenderer] (API 31+).
 *
 * Supports:
 *  - Paginated and scrolling modes (controlled by [ReaderSettings.scrollMode])
 *  - Pinch-to-zoom on the current page
 *  - Tap zones for page navigation in paginated mode
 *  - Position persistence via [onPageChanged] (page index)
 *
 * Pages are rendered to [Bitmap] on a background coroutine and displayed
 * via [Image]. Rendered bitmaps are cached for the visible window to
 * avoid re-rendering on every recomposition.
 *
 * @param fileUri      SAF content URI of the PDF.
 * @param initialPage  Restored page index from Room — 0-based.
 * @param onAutoScrollEnabledChanged Called with `false` when auto-scroll (#5) stops
 *                     itself — see AutoScroll.kt's doc — so the Settings-sheet
 *                     toggle reflects reality instead of staying "on" while nothing
 *                     is actually advancing any more.
 */
@Composable
fun PdfReaderScreen(
    fileUri: Uri,
    initialPage: Int,
    settings: ReaderSettings,
    onPageChanged: (Int) -> Unit,
    onCentreTap: () -> Unit,
    onAutoScrollEnabledChanged: (Boolean) -> Unit = {},
    scrollToPage: Int? = null,
    onScrollConsumed: () -> Unit = {},
) {
    val context       = LocalContext.current
    val scope         = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val screenWidthPx = context.resources.displayMetrics.widthPixels

    // ── PdfRenderer lifecycle ────────────────────────────────────────────────
    var renderer    by remember { mutableStateOf<PdfRenderer?>(null) }
    var pageCount   by remember { mutableIntStateOf(0) }
    var openError   by remember { mutableStateOf<String?>(null) }
    val renderMutex = remember { Mutex() }

    DisposableEffect(fileUri) {
        var pfd: android.os.ParcelFileDescriptor? = null
        try {
            pfd       = context.contentResolver.openFileDescriptor(fileUri, "r")
            if (pfd != null) {
                val r = PdfRenderer(pfd)
                renderer  = r
                pageCount = r.pageCount
            } else {
                openError = "Could not open the PDF — file may be inaccessible."
            }
        } catch (e: SecurityException) {
            openError = "Permission denied — the file cannot be read from this source."
        } catch (e: Exception) {
            openError = "Could not open the PDF: ${e.message}"
        }
        onDispose {
            renderer?.close()
            pfd?.close()
        }
    }

    // Show error if the file couldn't be opened
    openError?.let { msg ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text  = msg,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(32.dp),
            )
        }
        return
    }

    // ── Zoom state ────────────────────────────────────────────────────────────
    var scale  by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val zoomState = rememberTransformableState { zoomChange, panChange, _ ->
        scale  = (scale * zoomChange).coerceIn(1f, 4f)
        offset = if (scale == 1f) Offset.Zero else offset + panChange
    }

    val r = renderer ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    when (settings.scrollMode) {
        ScrollMode.SCROLLING   -> PdfScrollingView(
            renderer         = r,
            renderMutex      = renderMutex,
            pageCount        = pageCount,
            screenWidthPx    = screenWidthPx,
            initialPage      = initialPage,
            scrollToPage     = scrollToPage,
            onScrollConsumed = onScrollConsumed,
            onPageChanged    = onPageChanged,
            onCentreTap      = onCentreTap,
            settings         = settings,
            onAutoScrollEnabledChanged = onAutoScrollEnabledChanged,
        )
        ScrollMode.PAGINATED -> PdfPaginatedView(
            renderer         = r,
            renderMutex      = renderMutex,
            pageCount        = pageCount,
            screenWidthPx    = screenWidthPx,
            initialPage      = initialPage,
            scrollToPage     = scrollToPage,
            onScrollConsumed = onScrollConsumed,
            scale            = scale,
            offset           = offset,
            zoomState        = zoomState,
            onPageChanged    = onPageChanged,
            onCentreTap      = onCentreTap,
            settings         = settings,
            onAutoScrollEnabledChanged = onAutoScrollEnabledChanged,
        )
    }
}

// ── Scrolling mode ────────────────────────────────────────────────────────────

@Composable
private fun PdfScrollingView(
    renderer: PdfRenderer,
    renderMutex: Mutex,
    pageCount: Int,
    screenWidthPx: Int,
    initialPage: Int,
    scrollToPage: Int?,
    onScrollConsumed: () -> Unit,
    onPageChanged: (Int) -> Unit,
    onCentreTap: () -> Unit,
    settings: ReaderSettings,
    onAutoScrollEnabledChanged: (Boolean) -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage)
    val scope     = rememberCoroutineScope()

    // Bookmark navigation: animate to the requested page then clear the request
    LaunchedEffect(scrollToPage) {
        if (scrollToPage != null) {
            val target = scrollToPage.coerceIn(0, pageCount - 1)
            listState.animateScrollToItem(target)
            onScrollConsumed()
        }
    }

    // Auto-scroll (#5) — see AutoScroll.kt's doc for the mechanism and why a manual
    // drag or reaching the last page stops it (via onAutoScrollEnabledChanged)
    // rather than trying to resume silently.
    LaunchedEffect(settings.autoScrollEnabled, settings.autoScrollSpeed, listState) {
        if (settings.autoScrollEnabled) {
            listState.autoScroll(settings.autoScrollSpeed) {
                onAutoScrollEnabledChanged(false)
            }
        }
    }

    // Report page changes as user scrolls
    val currentPage by remember {
        derivedStateOf { listState.firstVisibleItemIndex }
    }
    LaunchedEffect(listState) {
        snapshotFlow { currentPage }
            .distinctUntilChanged()
            .collect { onPageChanged(it) }
    }

    LazyColumn(
        state    = listState,
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
        items(pageCount) { pageIndex ->
            PdfPageImage(
                renderer      = renderer,
                renderMutex   = renderMutex,
                pageIndex     = pageIndex,
                screenWidthPx = screenWidthPx,
                modifier      = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
            )
        }
    }
}

// ── Paginated mode ────────────────────────────────────────────────────────────

@Composable
private fun PdfPaginatedView(
    renderer: PdfRenderer,
    renderMutex: Mutex,
    pageCount: Int,
    screenWidthPx: Int,
    initialPage: Int,
    scrollToPage: Int?,
    onScrollConsumed: () -> Unit,
    scale: Float,
    offset: Offset,
    zoomState: androidx.compose.foundation.gestures.TransformableState,
    onPageChanged: (Int) -> Unit,
    onCentreTap: () -> Unit,
    settings: ReaderSettings,
    onAutoScrollEnabledChanged: (Boolean) -> Unit,
) {
    var currentPage by remember { mutableIntStateOf(initialPage.coerceIn(0, pageCount - 1)) }

    // Bookmark navigation: jump directly to the requested page
    LaunchedEffect(scrollToPage) {
        if (scrollToPage != null) {
            currentPage = scrollToPage.coerceIn(0, pageCount - 1)
            onScrollConsumed()
        }
    }
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp

    LaunchedEffect(currentPage) { onPageChanged(currentPage) }

    // Auto-scroll (#5) — no continuous ScrollableState in paginated mode, so this
    // is a timed page turn instead of a pixel scroll (see AutoScroll.kt's doc).
    // Stops itself (via onAutoScrollEnabledChanged) at the last page.
    LaunchedEffect(settings.autoScrollEnabled, settings.autoScrollSpeed, pageCount) {
        if (settings.autoScrollEnabled) {
            autoAdvancePages(settings.autoScrollSpeed, onFinished = { onAutoScrollEnabledChanged(false) }) {
                if (currentPage < pageCount - 1) {
                    currentPage++
                    true
                } else {
                    false
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .transformable(zoomState)
            .pointerInput(Unit) {
                detectTapGestures { tapOffset ->
                    val xDp    = tapOffset.x / density
                    val width  = size.width / density
                    when {
                        xDp < width * 0.33f -> {
                            if (currentPage > 0) currentPage--
                        }
                        xDp > width * 0.67f -> {
                            if (currentPage < pageCount - 1) currentPage++
                        }
                        else -> onCentreTap()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        PdfPageImage(
            renderer      = renderer,
            renderMutex   = renderMutex,
            pageIndex     = currentPage,
            screenWidthPx = screenWidthPx,
            modifier      = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .graphicsLayer(
                    scaleX         = scale,
                    scaleY         = scale,
                    translationX   = offset.x,
                    translationY   = offset.y,
                ),
        )

        // Page indicator
        Text(
            text  = "${currentPage + 1} / $pageCount",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp),
        )
    }
}

// ── Page renderer ─────────────────────────────────────────────────────────────

@Composable
private fun PdfPageImage(
    renderer: PdfRenderer,
    renderMutex: Mutex,
    pageIndex: Int,
    screenWidthPx: Int,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var bitmap by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }

    // Recycle the bitmap when this composable is disposed (item scrolls out of
    // view in scrolling mode, or page changes in paginated mode). Without this,
    // every page that was ever viewed retains its ~4MB bitmap in native memory.
    DisposableEffect(pageIndex) {
        onDispose {
            bitmap?.recycle()
            bitmap = null
        }
    }

    LaunchedEffect(pageIndex, screenWidthPx) {
        renderMutex.withLock {
            val old = bitmap
            bitmap = withContext(Dispatchers.IO) {
                renderPage(renderer, pageIndex, screenWidthPx)
            }
            old?.recycle()
        }
    }

    bitmap?.let {
        Image(
            bitmap      = it.asImageBitmap(),
            contentDescription = "Page ${pageIndex + 1}",
            modifier    = modifier,
        )
    } ?: Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
    }
}

private fun renderPage(renderer: PdfRenderer, pageIndex: Int, widthPx: Int): Bitmap {
    val page   = renderer.openPage(pageIndex)
    val ratio  = page.height.toFloat() / page.width.toFloat()
    val height = (widthPx * ratio).toInt()

    val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(android.graphics.Color.WHITE)
    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    page.close()
    return bitmap
}
