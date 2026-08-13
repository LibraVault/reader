import SwiftUI
import WebKit

/// Maps LibraVault's reading theme to one of Mermaid's own built-in theme names —
/// the iOS mirror of Android's `mermaidThemeName` (feature/reader/.../mermaid/MermaidTheme.kt).
///
/// v1 scope: built-in theme names only, not custom `themeVariables` — sepia maps to
/// `neutral` as the closest stock theme, not a hand-tuned colour match for
/// LibraVault's actual sepia palette. Deferred for the same reason Android's is.
func mermaidThemeName(for readingTheme: ReadingTheme) -> String {
    switch readingTheme {
    case .light: return "default"
    case .dark: return "dark"
    case .sepia: return "neutral"
    }
}

/// Serves the bundled Mermaid renderer to its own `WKWebView` from a custom
/// `mermaid-local://` scheme — the iOS equivalent of Android's `WebViewAssetLoader`
/// serving a `https://appassets.androidplatform.net` virtual origin. Only two
/// resources exist under this scheme: `mermaid_host.html` and `mermaid.min.js`
/// (both committed under ios/LibraVaultApp/LibraVault/LibraVault/Mermaid/, bundled the
/// same way Fonts/Lora.ttf already is — this repo's existing precedent for a non-Swift
/// resource folder inside the synchronized "LibraVault" group). Anything else — any
/// path this handler doesn't recognize — fails the request outright rather than
/// falling through to a real fetch.
///
/// **Known asymmetry with Android, stated plainly rather than implied away:** Android's
/// `shouldInterceptRequest` lets the native layer inspect and block *every* resource
/// request the page makes, regardless of scheme — a second, independent lockdown layer
/// on top of the page's own CSP. WKWebView has no equivalent hook for arbitrary
/// `https://` fetch/XHR calls; a custom `WKURLSchemeHandler` only ever sees requests
/// for its own registered scheme. On iOS, mermaid_host.html's `Content-Security-Policy:
/// default-src 'none'` (blocking fetch/XHR/WebSocket entirely) is the *primary* defense
/// against the bundled JS reaching the network, not defense-in-depth alongside a native
/// blocker the platform doesn't expose.
final class MermaidResourceSchemeHandler: NSObject, WKURLSchemeHandler {
    static let scheme = "mermaid-local"

    func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
        guard
            let url = urlSchemeTask.request.url,
            let fileName = url.pathComponents.last,
            let resourceURL = Bundle.main.url(forResource: fileName, withExtension: nil, subdirectory: "Mermaid")
                ?? Bundle.main.url(forResource: (fileName as NSString).deletingPathExtension, withExtension: (fileName as NSString).pathExtension),
            let data = try? Data(contentsOf: resourceURL)
        else {
            urlSchemeTask.didFailWithError(URLError(.fileDoesNotExist))
            return
        }

        let mimeType = fileName.hasSuffix(".html") ? "text/html" : "application/javascript"
        let response = URLResponse(url: url, mimeType: mimeType, expectedContentLength: data.count, textEncodingName: "utf-8")
        urlSchemeTask.didReceive(response)
        urlSchemeTask.didReceive(data)
        urlSchemeTask.didFinish()
    }

    func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) {
        // Nothing in-flight to cancel — start(_:) above is synchronous.
    }
}

/// Renders Mermaid diagram `source` via a bundled, offline copy of mermaid.js in a
/// WKWebView (#121) — mirrors Android's MermaidDiagramView.kt; see that file's doc
/// comment for the fuller design rationale (one WebView per diagram, fallback-on-error
/// philosophy), which applies identically here.
struct MermaidDiagramView: UIViewRepresentable {
    let source: String
    let readingTheme: ReadingTheme
    /// If rendering hasn't produced a height or an error within this window, treated
    /// as a failure — mirrors Android's `renderTimeoutMs`.
    var renderTimeoutSeconds: TimeInterval = 8

    @Binding var height: CGFloat?
    @Binding var renderError: String?

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.setURLSchemeHandler(MermaidResourceSchemeHandler(), forURLScheme: MermaidResourceSchemeHandler.scheme)
        configuration.userContentController.add(context.coordinator, name: "reportHeight")
        configuration.userContentController.add(context.coordinator, name: "onRenderError")
        // No cookies/cache/localStorage survive across diagrams or app launches —
        // there is nothing here that should ever need to persist.
        configuration.websiteDataStore = .nonPersistent()

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.isScrollEnabled = false
        if let url = URL(string: "\(MermaidResourceSchemeHandler.scheme)://local/mermaid_host.html") {
            webView.load(URLRequest(url: url))
        }
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        // Keeps Coordinator.parent current — without this, didFinish(navigation:)'s
        // one-time initial render (fired from makeUIView's webView.load, before any
        // SwiftUI update has necessarily run) could read a stale `source`/`readingTheme`
        // if either changed in the brief window before that first navigation completes.
        context.coordinator.parent = self
        context.coordinator.render(in: webView, source: source, theme: mermaidThemeName(for: readingTheme))
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        var parent: MermaidDiagramView
        private var pageReady = false
        /// The (source, theme) pair already requested — re-render only fires again
        /// when this actually changes, avoiding a redundant re-render on every SwiftUI
        /// body recomputation that leaves `source`/`readingTheme` unchanged.
        private var lastRequested: String?
        private var pendingTimeoutTask: Task<Void, Never>?

        init(_ parent: MermaidDiagramView) {
            self.parent = parent
        }

        func render(in webView: WKWebView, source: String, theme: String) {
            let key = "\(source)|\(theme)"
            guard pageReady, lastRequested != key else { return }
            lastRequested = key
            parent.renderError = nil
            parent.height = nil

            let call = "renderDiagram(\(jsStringLiteral(source)), \(jsStringLiteral(theme)))"
            webView.evaluateJavaScript(call, completionHandler: nil)

            pendingTimeoutTask?.cancel()
            pendingTimeoutTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(self?.parent.renderTimeoutSeconds ?? 8) * 1_000_000_000)
                guard !Task.isCancelled, let self else { return }
                if self.parent.height == nil, self.parent.renderError == nil {
                    self.parent.renderError = "Diagram took too long to render"
                }
            }
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            pageReady = true
            render(in: webView, source: parent.source, theme: mermaidThemeName(for: parent.readingTheme))
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            parent.renderError = "Couldn't load the diagram renderer"
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            parent.renderError = "Couldn't load the diagram renderer"
        }

        /// Called on the main thread already — WKScriptMessageHandler's contract,
        /// unlike Android's @JavascriptInterface (background thread), needs no
        /// explicit hop before touching SwiftUI-observed state.
        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            switch message.name {
            case "reportHeight":
                if let px = message.body as? NSNumber {
                    parent.renderError = nil
                    parent.height = CGFloat(truncating: px)
                }
            case "onRenderError":
                let text = (message.body as? String) ?? "Unknown Mermaid render error"
                parent.renderError = text.isEmpty ? "Unknown Mermaid render error" : text
            default:
                break
            }
        }
    }
}

/// Owns the loading/rendered/fallback state machine [MermaidDiagramView] itself can't
/// (as a `UIViewRepresentable`, it has no view-level state of its own — everything
/// lives in `@Binding`s supplied from here) and is what `MarkdownBlockView`'s
/// `.mermaidDiagram` case actually instantiates. Mirrors Android's `MermaidDiagramView`
/// composable's three-way `when` (loading spinner / real WebView / fallback text).
struct MermaidDiagramBlockView: View {
    let source: String
    let readingTheme: ReadingTheme
    let colors: LibraVaultColorScheme
    let fontSize: Double

    @State private var height: CGFloat?
    @State private var renderError: String?

    var body: some View {
        // A single MermaidDiagramView instance, always present and never inside an
        // if/else branch — SwiftUI tears down and recreates a view whose identity
        // changes across an if/else transition, which here would mean a fresh
        // WKWebView (and mermaid.js reloading from scratch) every time loading
        // finishes. Loading/fallback UI are overlays on top of it instead, so the one
        // underlying WebView instance persists across the whole render lifecycle.
        ZStack {
            MermaidDiagramView(source: source, readingTheme: readingTheme, height: $height, renderError: $renderError)
                .frame(height: height ?? 0)
                .opacity(renderError == nil ? 1 : 0)

            if renderError == nil && height == nil {
                ProgressView()
                    .frame(maxWidth: .infinity, minHeight: 120)
            }

            if let renderError {
                VStack(alignment: .leading, spacing: LibraVaultSpacing.xs) {
                    Text(renderError)
                        .font(.caption)
                        .foregroundStyle(.red)
                    Text(source)
                        .font(.system(size: 14 * fontSize, design: .monospaced))
                        .foregroundStyle(colors.onBackground)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(LibraVaultSpacing.sm)
                .background(colors.surface)
                .clipShape(RoundedRectangle(cornerRadius: 8))
            }
        }
    }
}

/// Safe embedding of an arbitrary string into a JS call — the Swift equivalent of
/// Android's `org.json.JSONObject.quote(...)`. `JSONSerialization` on a one-element
/// array is a standard, dependency-free way to get correct JSON/JS string escaping
/// (quotes, backslashes, newlines, unicode) without hand-rolling it.
private func jsStringLiteral(_ value: String) -> String {
    guard
        let data = try? JSONSerialization.data(withJSONObject: [value]),
        let json = String(data: data, encoding: .utf8),
        json.hasPrefix("[\""), json.hasSuffix("\"]")
    else {
        return "\"\""
    }
    return String(json.dropFirst().dropLast())
}
