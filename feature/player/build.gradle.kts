plugins {
    id("libravault.android.feature")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.feature.player"

    lint {
        baseline = file("lint-baseline.xml")
    }

    // Standard Android unit-test config: android.os.Bundle / android.util.Log etc.
    // return defaults instead of throwing "not mocked" RuntimeExceptions. Required
    // for tests that touch the Media3 CommandButton / SessionCommand API surface.
    // isIncludeAndroidResources additionally lets Robolectric resolve the merged
    // manifest/resources PlayerScreenLandscapeTest's createComposeRule() needs.
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:storage"))
    implementation(project(":core:logger"))
    implementation(project(":core:database"))
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Media3 — ExoPlayer, MediaSession, UI
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    // Pre-playback chapter metadata (MetadataRetriever) — media3-inspector, added in 1.11.
    implementation(libs.media3.inspector)

    // Guava for ListenableFuture (MediaController.buildAsync)
    implementation("com.google.guava:guava:33.2.1-android")
    
    // Test dependencies
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation(libs.bundles.testing.jvm)

    // Compose UI test for the landscape player layout (PlayerScreenLandscapeTest)
    // — Robolectric hosts a real Compose tree on the JVM, same setup as
    // feature:settings' TtsSettingsSectionTest. JUnit4 (Compose test rules are
    // JUnit4-only) runs alongside this module's JUnit5 tests via the vintage engine.
    testImplementation(libs.bundles.testing.android)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit5.vintage.engine)
    // Debug-only manifest declaring the ComponentActivity that Compose's
    // createComposeRule() launches to host test content — picked up by unit
    // tests too via testOptions.unitTests.isIncludeAndroidResources above.
    debugImplementation(libs.compose.ui.test.manifest)
}
