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
}

dependencies {
    implementation(project(":core:storage"))
    implementation(project(":core:logger"))
    implementation(project(":core:database"))
    implementation(project(":feature:player"))
    implementation("androidx.media3:media3-session:1.3.1")
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
    // commonmark-java, whose AST we also reuse for TOC extraction. Pinned to 0.28.0
    // (pre-0.30.0) deliberately — later releases require Kotlin >= 2.1's metadata
    // format, incompatible with this project's Kotlin 2.0.0. That also means no GFM
    // tables yet (added in 0.30.0) — dropped from v1 on both platforms for the same
    // reason iOS dropped them (see feature/reader/markdown/MarkdownReaderScreen.kt);
    // revisit once a Kotlin bump is deliberately reviewed on its own.
    implementation(libs.markdown.renderer.m3)

    // DocumentFile — walks the SAF vault tree to resolve a Markdown file's relative
    // image references (MarkdownAssetResolver lives in core:storage; the resolved
    // DocumentFile/Uri types are used directly here too).
    implementation(libs.androidx.documentfile)

    // Fragment interop for hosting Readium EpubNavigatorFragment inside Compose
    implementation("androidx.fragment:fragment-ktx:1.8.1")
    implementation("androidx.fragment:fragment-compose:1.8.1")

    // ── Instrumentation tests ──
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
