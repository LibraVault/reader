package xyz.libravault.feature.vault

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.TransformableState
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.libravault.core.vaultcontent.VaultMemfdFallback
import xyz.libravault.core.vaultcontent.VaultProxyFdHost
import xyz.libravault.core.vaultcrypto.VaultFileReader

/**
 * Vault-native PDF reader: continuous-scroll or paginated (page-by-page,
 * swipe/tap navigation), switched via [settings]' `scrollMode` — the same
 * two modes `feature:reader`'s `PdfReaderScreen` offers, ported here rather
 * than shared (see [VaultReaderSettings]'s doc). One bitmap per page either
 * way; proxy fd first (validated on real hardware in the Phase 0 spike —
 * implementation plan §D.0.RESULTS), falling back to `memfd` if the proxy fd
 * fails on this device — the same primary/fallback shape
 * `core:vaultcontent`'s `VaultPdfFileDescriptor.kt` was built for.
 *
 * [reader] is not closed here — [VaultProxyFdCallback.onRelease] (proxy fd
 * path) or the caller's own cleanup (memfd path, and [VaultReaderViewModel]'s
 * `onCleared` as a backstop either way) owns that; [VaultFileReader.close]
 * is idempotent, so the backstop is harmless even when the proxy fd path
 * already closed it.
 *
 * [onPageChanged] reports the current page (topmost visible in scrolling
 * mode, the displayed page in paginated mode) — the position reference
 * [VaultReaderViewModel.addBookmark] bookmarks against. [scrollToPage]
 * drives bookmark-navigation: set it to animate/jump to that page, then call
 * [onScrollConsumed] once consumed (mirrors `feature:reader`'s
 * `PdfReaderScreen` exactly).
 */
@Composable
fun VaultPdfReaderScreen(
    reader: VaultFileReader,
    settings: VaultReaderSettings,
    modifier: Modifier = Modifier,
    onPageChanged: (Int) -> Unit = {},
    scrollToPage: Int? = null,
    onScrollConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var openError by remember { mutableStateOf<String?>(null) }
    val renderMutex = remember { Mutex() }
    val density = LocalDensity.current.density
    val screenWidthPx = (LocalConfiguration.current.screenWidthDp * density).toInt()

    DisposableEffect(reader) {
        val host = VaultProxyFdHost(context)
        var pfd: ParcelFileDescriptor? = null
        try {
            pfd = runCatching { host.open(reader) }.getOrElse { VaultMemfdFallback.open(reader) }
            val r = PdfRenderer(pfd)
            renderer = r
            pageCount = r.pageCount
        } catch (e: Exception) {
            openError = "Could not open the PDF: ${e.message}"
        }
        onDispose {
            renderer?.close()
            pfd?.close()
            host.close()
        }
    }

    openError?.let { msg ->
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(msg, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(32.dp))
        }
        return
    }

    val r = renderer ?: run {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    // ── Zoom state (paginated mode only) ────────────────────────────────────
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val zoomState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 4f)
        offset = if (scale == 1f) Offset.Zero else offset + panChange
    }

    when (settings.scrollMode) {
        VaultScrollMode.SCROLLING -> VaultPdfScrollingView(
            renderer         = r,
            renderMutex      = renderMutex,
            pageCount        = pageCount,
            screenWidthPx    = screenWidthPx,
            scrollToPage     = scrollToPage,
            onScrollConsumed = onScrollConsumed,
            onPageChanged    = onPageChanged,
            modifier         = modifier,
        )
        VaultScrollMode.PAGINATED -> VaultPdfPaginatedView(
            renderer         = r,
            renderMutex      = renderMutex,
            pageCount        = pageCount,
            screenWidthPx    = screenWidthPx,
            scrollToPage     = scrollToPage,
            onScrollConsumed = onScrollConsumed,
            scale            = scale,
            offset           = offset,
            zoomState        = zoomState,
            onPageChanged    = onPageChanged,
            modifier         = modifier,
        )
    }
}

// ── Scrolling mode ──────────────────────────────────────────────────────────

@Composable
private fun VaultPdfScrollingView(
    renderer: PdfRenderer,
    renderMutex: Mutex,
    pageCount: Int,
    screenWidthPx: Int,
    scrollToPage: Int?,
    onScrollConsumed: () -> Unit,
    onPageChanged: (Int) -> Unit,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()

    // Bookmark navigation: animate to the requested page then clear the request.
    LaunchedEffect(scrollToPage, pageCount) {
        if (scrollToPage != null && pageCount > 0) {
            listState.animateScrollToItem(scrollToPage.coerceIn(0, pageCount - 1))
            onScrollConsumed()
        }
    }

    // Report page changes as the user scrolls.
    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    LaunchedEffect(listState) {
        snapshotFlow { currentPage }
            .distinctUntilChanged()
            .collect { onPageChanged(it) }
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        items(pageCount) { pageIndex ->
            VaultPdfPageImage(
                renderer      = renderer,
                renderMutex   = renderMutex,
                pageIndex     = pageIndex,
                screenWidthPx = screenWidthPx,
                modifier      = Modifier.fillMaxWidth().wrapContentHeight(),
            )
        }
    }
}

// ── Paginated mode ──────────────────────────────────────────────────────────

@Composable
private fun VaultPdfPaginatedView(
    renderer: PdfRenderer,
    renderMutex: Mutex,
    pageCount: Int,
    screenWidthPx: Int,
    scrollToPage: Int?,
    onScrollConsumed: () -> Unit,
    scale: Float,
    offset: Offset,
    zoomState: TransformableState,
    onPageChanged: (Int) -> Unit,
    modifier: Modifier,
) {
    var currentPage by remember { mutableIntStateOf(0) }

    // Clamp once the page count is known (0 while the renderer is still opening).
    LaunchedEffect(pageCount) {
        if (pageCount > 0) currentPage = currentPage.coerceIn(0, pageCount - 1)
    }

    // Bookmark navigation: jump directly to the requested page.
    LaunchedEffect(scrollToPage, pageCount) {
        if (scrollToPage != null && pageCount > 0) {
            currentPage = scrollToPage.coerceIn(0, pageCount - 1)
            onScrollConsumed()
        }
    }

    LaunchedEffect(currentPage) { onPageChanged(currentPage) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .transformable(zoomState)
            .pointerInput(Unit) {
                detectTapGestures { tapOffset ->
                    val xDp   = tapOffset.x / density
                    val width = size.width / density
                    when {
                        xDp < width * 0.33f -> if (currentPage > 0) currentPage--
                        xDp > width * 0.67f -> if (currentPage < pageCount - 1) currentPage++
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (pageCount > 0) {
            VaultPdfPageImage(
                renderer      = renderer,
                renderMutex   = renderMutex,
                pageIndex     = currentPage,
                screenWidthPx = screenWidthPx,
                modifier      = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .graphicsLayer(
                        scaleX       = scale,
                        scaleY       = scale,
                        translationX = offset.x,
                        translationY = offset.y,
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
}

@Composable
private fun VaultPdfPageImage(
    renderer: PdfRenderer,
    renderMutex: Mutex,
    pageIndex: Int,
    screenWidthPx: Int,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(pageIndex) {
        onDispose {
            bitmap?.recycle()
            bitmap = null
        }
    }

    LaunchedEffect(pageIndex, screenWidthPx) {
        renderMutex.withLock {
            val old = bitmap
            bitmap = withContext(Dispatchers.IO) { renderVaultPdfPage(renderer, pageIndex, screenWidthPx) }
            old?.recycle()
        }
    }

    bitmap?.let {
        Image(bitmap = it.asImageBitmap(), contentDescription = "Page ${pageIndex + 1}", modifier = modifier)
    } ?: Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
    }
}

private fun renderVaultPdfPage(renderer: PdfRenderer, pageIndex: Int, widthPx: Int): Bitmap {
    val page   = renderer.openPage(pageIndex)
    val ratio  = page.height.toFloat() / page.width.toFloat()
    val height = (widthPx * ratio).toInt()

    val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(android.graphics.Color.WHITE)
    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    page.close()
    return bitmap
}
