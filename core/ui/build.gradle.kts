plugins {
    id("libravault.android.library")
    id("libravault.android.compose")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.core.ui"

    // Robolectric-hosted Compose UI tests (LibravaultThemeTest) need the merged
    // manifest/resources to resolve the ComponentActivity that createComposeRule()
    // launches to host content - see the ui-test-manifest dependency below.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(platform(libs.compose.bom))
    api(libs.bundles.compose)
    // WindowCompat, for reactively matching status bar icon color to the active theme.
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.bundles.testing.jvm)
    testRuntimeOnly(libs.junit5.engine)
    // Compose UI test for LibravaultTheme - Robolectric hosts a real Compose tree on
    // the JVM, no emulator/device needed. JUnit4 (Compose test rules are JUnit4-only)
    // runs alongside the module's JUnit5 tests via the vintage engine.
    testImplementation(libs.bundles.testing.android)
    testImplementation(libs.junit)
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
    // ComponentActivity, for createAndroidComposeRule<ComponentActivity>() in
    // LibravaultThemeTest — needs a real hosting Activity to assert against its Window.
    testImplementation(libs.androidx.activity.compose)
    // Debug-only manifest declaring the ComponentActivity that Compose's
    // createComposeRule() launches to host test content - picked up by unit
    // tests too via testOptions.unitTests.isIncludeAndroidResources above.
    debugImplementation(libs.compose.ui.test.manifest)
}
