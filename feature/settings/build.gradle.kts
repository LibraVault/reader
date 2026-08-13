plugins {
    id("libravault.android.feature")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.feature.settings"

    flavorDimensions += "distribution"
    productFlavors {
        create("fdroid") { dimension = "distribution" }
        create("play")   { dimension = "distribution" }
    }

    // Robolectric-hosted Compose UI tests (TtsSettingsSectionTest) need the merged
    // manifest/resources to resolve the ComponentActivity that createComposeRule()
    // launches to host content - see the ui-test-manifest dependency below.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":core:storage"))
    api(project(":core:logger"))
    api(project(":core:tts"))
    // hilt-android/hilt-android-compiler come from the libravault.android.hilt
    // convention plugin (applied via libravault.android.feature above) — declaring
    // them again here just duplicated a hardcoded version that drifted from it.
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("com.google.zxing:core:3.5.3")
    // OkHttp is play-only — fdroid build has no network calls
    "playImplementation"("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // Compose UI test for TtsSettingsSection - Robolectric hosts a real Compose
    // tree on the JVM, no emulator/device needed. JUnit4 (Compose test rules are
    // JUnit4-only) runs alongside the module's JUnit5 tests via the vintage engine.
    testImplementation(libs.bundles.testing.android)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit5.vintage.engine)
    // Debug-only manifest declaring the ComponentActivity that Compose's
    // createComposeRule() launches to host test content - picked up by unit
    // tests too via testOptions.unitTests.isIncludeAndroidResources above.
    debugImplementation(libs.compose.ui.test.manifest)

    // SettingsViewModelTest constructs ~12 MockK mocks per test plus mocks
    // Uri.parse statically, which inflates Metaspace. Bump the heap so the
    // runner doesn't OOM at classload time.
    tasks.withType<Test>().configureEach {
        maxHeapSize = "2g"
        jvmArgs("-XX:MaxMetaspaceSize=768m")
    }
}
