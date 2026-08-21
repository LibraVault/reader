package xyz.libravault.feature.reader.markdown.mermaid

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.foundation.isSystemInDarkTheme
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
import xyz.libravault.core.ui.theme.resolved

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

    val bridge: MermaidJsBridge = remember {
        MermaidJsBridge(
            notifyHeightReported = { px ->
                Handler(Looper.getMainLooper()).post {
                    renderError = null
                    heightDp = with(density) { px.toDp() }
                }
            },
            notifyRenderError = { message ->
                Handler(Looper.getMainLooper()).post {
                    renderError = message.ifBlank { "Unknown Mermaid render error" }
                }
            },
        )
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
            // Lint false positive, confirmed by direct inspection, not just suppressed
            // on faith: MermaidJsBridge.reportHeight/onRenderError ARE both real,
            // top-level, @JavascriptInterface-annotated methods (see that class) —
            // the actual API-17 security property this check exists for is satisfied.
            // What the checker can't do is trace `bridge`'s concrete type through
            // Compose's `remember<T>(...)` — a generic call into a third-party
            // library it has no special knowledge of — back to the MermaidJsBridge
            // constructor call inside the lambda; its data-flow tracking only follows
            // direct local-variable assignment chains (`Object o = new Foo(); Object t
            // = o;`), not through an arbitrary intervening function call. Restructuring
            // this call to dodge the tracker would make the code worse for a tool
            // limitation, not a real annotation gap.
            @Suppress("JavascriptInterface")
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

    // Resolved once per composition — isSystemInDarkTheme() reads LocalConfiguration, so
    // this recomposes automatically when the OS appearance setting changes while SYSTEM is
    // selected, same as LibravaultTheme's own resolution (#370's "updates live" acceptance
    // criterion, extended to the Mermaid diagram theme).
    val resolvedTheme = readingTheme.resolved(isSystemInDarkTheme())

    // rememberUpdatedState so a source/theme change picked up while a previous render
    // is still in flight doesn't re-fire this effect using a stale closure — LaunchedEffect
    // itself is still correctly keyed on the raw values below to trigger a fresh render.
    val currentSource by rememberUpdatedState(source)
    val currentTheme by rememberUpdatedState(mermaidThemeName(resolvedTheme))

    // Keyed on resolvedTheme rather than the raw readingTheme: while SYSTEM is selected,
    // only resolvedTheme actually changes when the OS appearance flips, so keying on the
    // raw enum would miss re-rendering the diagram for that transition.
    LaunchedEffect(pageReady, source, resolvedTheme) {
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

/**
 * The `AndroidBridge` object passed to [WebView.addJavascriptInterface]. Deliberately a
 * real, named, top-level class — not an anonymous `object { ... }` expression inside
 * the composable, which is what this started as. That anonymous form fails Android
 * Lint's `JavascriptInterface` check ("None of the methods in the added interface have
 * been annotated with @JavascriptInterface; they will not be visible in API 17"), and
 * the underlying cause isn't just a lint nitpick: Kotlin anonymous object expressions
 * compile to synthetic classes, and `addJavascriptInterface`'s reflection-based method
 * lookup for `@JavascriptInterface` isn't reliable against those — since API 17,
 * Android *requires* the annotation to expose a method to JS at all (the pre-17
 * unannotated behaviour was a real remote-code-execution vector), so a bridge method
 * lint can't see is a bridge method that may silently never be callable from JS, not
 * just a suppressible warning.
 *
 * Callbacks rather than direct Compose state access: keeps this class free of any
 * Compose/`@Composable` dependency, so it stays a plain, ordinary Kotlin class Lint (and
 * the JVM reflection `addJavascriptInterface` itself performs) has no ambiguity about.
 */
internal class MermaidJsBridge(
    private val notifyHeightReported: (px: Float) -> Unit,
    private val notifyRenderError: (message: String) -> Unit,
) {
    /** Called from JS on the WebView's own thread, not the main thread — callers must
     *  hop back to the main thread themselves before touching Compose state. Method
     *  name is part of the JS-facing contract (see mermaid_host.html's
     *  `AndroidBridge.reportHeight(...)` call) — do not rename without updating that
     *  asset too. Deliberately not named the same as the constructor property above:
     *  Kotlin resolves a call by name to the member function first, so a same-named
     *  property holding the callback would never actually be reached. */
    @JavascriptInterface
    fun reportHeight(px: Float) {
        notifyHeightReported(px)
    }

    /** See [reportHeight]'s doc comment — same JS-facing-name-vs-property-name
     *  reasoning applies here. */
    @JavascriptInterface
    fun onRenderError(message: String) {
        notifyRenderError(message)
    }
}

