plugins {
    id("libravault.android.feature")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.feature.library"

    // Robolectric-hosted Compose UI tests (FormatFilterRowTest) need the merged
    // manifest/resources to resolve the ComponentActivity that createComposeRule()
    // launches to host content — see the ui-test-manifest dependency below.
    // Same setup as :feature:settings (TtsSettingsSectionTest).
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:storage"))
    implementation(project(":core:logger"))
    implementation(project(":feature:player"))
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(libs.media3.session)
    implementation("com.google.guava:guava:33.2.1-android")

    // Compose UI test for FormatFilterRow — Robolectric hosts a real Compose tree
    // on the JVM, no emulator/device needed. JUnit4 (Compose test rules are
    // JUnit4-only) runs alongside this module's JUnit5 tests via the vintage
    // engine. Mirrors :feature:settings' setup.
    testImplementation(libs.bundles.testing.android)
    testImplementation(libs.junit)
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
    // Debug-only manifest declaring the ComponentActivity that Compose's
    // createComposeRule() launches to host test content — picked up by unit
    // tests too via testOptions.unitTests.isIncludeAndroidResources above.
    debugImplementation(libs.compose.ui.test.manifest)
}
