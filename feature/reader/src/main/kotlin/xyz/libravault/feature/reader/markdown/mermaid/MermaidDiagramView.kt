package xyz.libravault.feature.reader.markdown.mermaid

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.json.JSONObject
import xyz.libravault.core.ui.theme.ReadingTheme

/**
 * Renders Mermaid diagram [source] via a bundled, offline copy of mermaid.js in a
 * WebView (#121) — the only viable renderer, since there is no native Kotlin Mermaid
 * implementation. Never touches the network: [WebViewAssetLoader] serves
 * mermaid_host.html and mermaid.min.js (committed assets, see
 * feature/reader/src/main/assets/mermaid/) from a local
 * `https://appassets.androidplatform.net` virtual origin, and [shouldInterceptRequest]
 * below blocks every other request outright rather than letting it fall through to a
 * real network fetch — the host page's own CSP (`default-src 'none'`) is a second,
 * independent layer of the same lockdown, not a substitute for this one.
 *
 * Falls back to the raw fenced source as plain text — never blank space — if Mermaid
 * itself reports invalid syntax (see mermaid_host.html's try/catch around
 * `mermaid.render`) or the diagram simply never finishes rendering within
 * [renderTimeoutMs].
 *
 * One WebView per diagram (matching #121's original phase-0 note that this — not an
 * offscreen rasterize-to-bitmap pool — was the simpler starting point to prove the
 * pipeline works) rather than pooling: a document with many diagrams paying one
 * WebView's real memory cost each is the accepted v1 tradeoff, not something this view
 * tries to hide.
 */
@SuppressLint("SetJavaScriptEnabled") // deliberate — see the class doc's network-lockdown reasoning
@Composable
fun MermaidDiagramView(
    source: String,
    readingTheme: ReadingTheme,
    modifier: Modifier = Modifier,
    renderTimeoutMs: Long = 8_000,
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    var heightDp by remember { mutableStateOf<Dp?>(null) }
    var renderError by remember { mutableStateOf<String?>(null) }
    var pageReady by remember { mutableStateOf(false) }

    val assetLoader = remember(context) {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }

    val bridge = remember {
        object {
            /** Called from JS on the WebView's own thread, not the main thread — every
             *  write here must hop back via [Handler] before touching Compose state,
             *  which is only safe to mutate from the main thread. */
            @JavascriptInterface
            fun reportHeight(px: Float) {
                Handler(Looper.getMainLooper()).post {
                    renderError = null
                    heightDp = with(density) { px.toDp() }
                }
            }

            @JavascriptInterface
            fun onRenderError(message: String) {
                Handler(Looper.getMainLooper()).post {
                    renderError = message.ifBlank { "Unknown Mermaid render error" }
                }
            }
        }
    }

    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            // WebViewAssetLoader's virtual https:// origin is how local content is
            // served — raw file:// access is never needed and is left off deliberately.
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            addJavascriptInterface(bridge, "AndroidBridge")
            webViewClient = object : WebViewClientCompat() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    // Anything the asset loader doesn't recognize is blocked outright
                    // (an empty response), not allowed to fall through to WebView's
                    // own default handling, which would attempt a real network fetch.
                    return assetLoader.shouldInterceptRequest(request.url)
                        ?: WebResourceResponse("text/plain", "utf-8", null)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    pageReady = true
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceErrorCompat,
                ) {
                    Handler(Looper.getMainLooper()).post {
                        renderError = "Couldn't load the diagram renderer"
                    }
                }
            }
            loadUrl("https://appassets.androidplatform.net/assets/mermaid/mermaid_host.html")
        }
    }

    DisposableEffect(Unit) {
        onDispose { webView.destroy() }
    }

    // rememberUpdatedState so a source/theme change picked up while a previous render
    // is still in flight doesn't re-fire this effect using a stale closure — LaunchedEffect
    // itself is still correctly keyed on the raw values below to trigger a fresh render.
    val currentSource by rememberUpdatedState(source)
    val currentTheme by rememberUpdatedState(mermaidThemeName(readingTheme))

    LaunchedEffect(pageReady, source, readingTheme) {
        if (!pageReady) return@LaunchedEffect
        renderError = null
        heightDp = null
        val call = "renderDiagram(${JSONObject.quote(currentSource)}, ${JSONObject.quote(currentTheme)})"
        webView.evaluateJavascript(call, null)

        kotlinx.coroutines.delay(renderTimeoutMs)
        if (heightDp == null && renderError == null) {
            renderError = "Diagram took too long to render"
        }
    }

    when {
        renderError != null -> MermaidFallback(source = source, message = renderError!!, modifier = modifier)
        heightDp == null -> MermaidLoading(modifier = modifier)
        else -> AndroidView(
            factory = { webView },
            modifier = modifier.fillMaxWidth().height(heightDp!!),
        )
    }
}

@Composable
private fun MermaidLoading(modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier.fillMaxWidth().height(120.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

/**
 * Shown for invalid Mermaid syntax, a render timeout, or a renderer load failure —
 * the raw fenced source as plain monospace text, matching how a normal (non-mermaid)
 * code block already renders elsewhere in this viewer, so a broken diagram degrades to
 * "unstyled code" rather than a blank gap or a dialog.
 */
@Composable
private fun MermaidFallback(source: String, message: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = message,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = source,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

