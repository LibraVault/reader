plugins {
    id("libravault.android.feature")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.feature.vault"

    // Robolectric-hosted tests touch real android.net.Uri / Compose test rules,
    // matching core:vaultcontent's Phase 3 tests and feature:settings's
    // TtsSettingsSectionTest — see VaultSessionManagerTest / CreateVaultViewModelTest.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":core:vaultstore"))
    implementation(project(":core:logger"))
    // hilt-android/hilt-android-compiler come from the libravault.android.hilt
    // convention plugin (applied via libravault.android.feature above).
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation(libs.navigation.compose)
    implementation(libs.bundles.lifecycle)
    // BackHandler (CreateVaultScreen's multi-step wizard needs gesture/system
    // back to agree with the AppBar's own back arrow) — not pulled in by the
    // shared compose convention plugin, same as app/build.gradle.kts.
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    // Recovery-key QR display — same library/version feature:settings already
    // uses for donation-address QR codes (DonateScreen.kt), pinned via the
    // catalog here rather than repeating that module's inline version string.
    implementation("com.google.zxing:core:${libs.versions.zxing.get()}")

    testImplementation(libs.bundles.testing.jvm)
    testImplementation(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.engine)

    // Robolectric for the small slice of Android-framework-touching code
    // (ProcessLifecycleOwner in VaultSessionManager, Compose state in
    // CreateVaultViewModel/UnlockVaultViewModel) — JUnit4 runs alongside this
    // module's JUnit5 tests via the vintage engine, matching core:vaultcontent.
    testImplementation(libs.bundles.testing.android)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit5.vintage.engine)
    debugImplementation(libs.compose.ui.test.manifest)
}
