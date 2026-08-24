plugins {
    id("libravault.android.feature")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.feature.reader"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    sourceSets {
        getByName("androidTest") {
            assets.srcDirs("src/androidTest/assets")
        }
    }

    // Robolectric-hosted Compose UI tests (MarkdownTableRenderingTest) need the merged
    // manifest/resources to resolve the ComponentActivity that createComposeRule()
    // launches to host content — see the ui-test-manifest dependency below. Same setup
    // as :feature:library (FormatFilterRowTest) / :feature:settings (TtsSettingsSectionTest).
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:storage"))
    implementation(project(":core:logger"))
    implementation(project(":core:database"))
    implementation(project(":core:tts"))
    // #505: vault-backed ContentSource resolution — VaultSessionManager/VaultStore
    // (session/lock state, bookmark/highlight round-trip) and the vault-native
    // content adapters (VaultProxyFdHost/VaultMemfdFallback for PDF,
    // VaultReadiumResource for EPUB) that used to be feature:vault-only.
    implementation(project(":core:vaultstore"))
    implementation(project(":core:vaultcontent"))
    implementation(project(":feature:player"))
    implementation(libs.media3.session)
    implementation("com.google.guava:guava:33.2.1-android")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")

    // Readium 3.x — EPUB rendering (BSD 3-Clause, Play Store compatible)
    implementation("org.readium.kotlin-toolkit:readium-shared:3.0.0-beta.2")
    implementation("org.readium.kotlin-toolkit:readium-streamer:3.0.0-beta.2")
    implementation("org.readium.kotlin-toolkit:readium-navigator:3.0.0-beta.2")

    // Jsoup — HTML parsing for TTS-friendly text extraction (review
    // finding #17 / WS3.6). ~600 KB. Not transitively pulled by Readium,
    // so we add it explicitly here.
    implementation("org.jsoup:jsoup:1.18.1")

    // AndroidX PDF Viewer — PDF rendering (API 31+, confirmed stable for v1)
    implementation("androidx.pdf:pdf-viewer:1.0.0-alpha04")

    // Markdown rendering — Compose-native CommonMark renderer. Chosen over Markwon
    // (TextView/AndroidView-based) since this codebase is Compose throughout; wraps
    // commonmark-java, whose AST we also reuse for TOC extraction. Pinned to 0.32.0
    // deliberately — that is the last release before 0.33.0's breaking change (async
    // `Markdown(String)` parsing, `MarkdownComponent` losing its `ColumnScope`, the
    // `level`->`depth` param rename). This project's per-section `Markdown()` calls
    // (see MarkdownReaderScreen.kt) depend on synchronous layout to record each
    // section's on-screen offset via onGloballyPositioned for TOC scrolling, so async
    // parsing needs its own deliberate migration rather than riding along with this
    // bump. 0.30.0 added GFM tables; 0.32.0 added the MarkdownTypography.table config
    // used to theme them (see MarkdownTheme.kt). Later releases also raise the
    // required Kotlin version well past this project's 2.2.10 (0.39.0 needs 2.3.0,
    // 0.42.0 needs 2.4.0) — revisit only alongside a deliberate Kotlin bump review.
    implementation(libs.markdown.renderer.m3)

    // DocumentFile — walks the SAF vault tree to resolve a Markdown file's relative
    // image references (MarkdownAssetResolver lives in core:storage; the resolved
    // DocumentFile/Uri types are used directly here too).
    implementation(libs.androidx.documentfile)

    // WebViewAssetLoader — see MermaidDiagramView.kt (#121). The only network-shaped
    // API this pulls in is entirely local: it serves bundled assets over a virtual
    // https:// origin so the WebView's same-origin/CSP rules apply normally, never
    // touching a real network.
    implementation(libs.androidx.webkit)

    // Fragment interop for hosting Readium EpubNavigatorFragment inside Compose
    implementation("androidx.fragment:fragment-ktx:1.8.1")
    implementation("androidx.fragment:fragment-compose:1.8.1")

    // ── Instrumentation tests ──
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")

    // Compose UI test for the Markdown renderer (MarkdownTableRenderingTest) —
    // Robolectric hosts a real Compose tree on the JVM, no emulator/device needed.
    // JUnit4 (Compose test rules are JUnit4-only) runs alongside this module's JUnit5
    // tests via the vintage engine. Mirrors :feature:library's/:feature:settings' setup.
    testImplementation(libs.bundles.testing.android)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit5.vintage.engine)
    // Debug-only manifest declaring the ComponentActivity that Compose's
    // createComposeRule() launches to host test content — picked up by unit
    // tests too via testOptions.unitTests.isIncludeAndroidResources above.
    debugImplementation(libs.compose.ui.test.manifest)

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
