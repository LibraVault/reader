plugins {
    id("libravault.android.feature")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.feature.vault"

    // Robolectric-hosted tests touch real android.net.Uri / Compose test rules,
    // matching core:vaultcontent's Phase 3 tests and feature:settings's
    // TtsSettingsSectionTest.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":core:vaultstore"))
    // Phase 5b: the content-delivery adapters (Readium/Media3/PDF sources
    // over VaultFileReader) plus the storage-layer metadata/cover-art
    // extraction reused for import — see VaultImportViewModel and
    // VaultReadiumProvider/VaultPlayerScreen's doc comments for why each is
    // needed.
    implementation(project(":core:vaultcontent"))
    implementation(project(":core:storage"))
    implementation(project(":core:logger"))
    // PlayerSeekBar/PlaybackControls reuse for the vault audio player —
    // precedent for a feature module depending on feature:player already
    // exists (feature:reader, feature:library).
    implementation(project(":feature:player"))
    // hilt-android/hilt-android-compiler come from the libravault.android.hilt
    // convention plugin (applied via libravault.android.feature above).
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation(libs.navigation.compose)
    implementation(libs.bundles.lifecycle)
    // BackHandler (CreateVaultScreen's multi-step wizard needs gesture/system
    // back to agree with the AppBar's own back arrow) — not pulled in by the
    // shared compose convention plugin, same as app/build.gradle.kts.
    implementation(libs.androidx.activity.compose)
    // Recovery-key QR display — same library/version feature:settings already
    // uses for donation-address QR codes (DonateScreen.kt), pinned via the
    // catalog here rather than repeating that module's inline version string.
    implementation("com.google.zxing:core:${libs.versions.zxing.get()}")
    // EPUB rendering for vault content — same Readium artifacts/versions as
    // feature:reader, pinned via the catalog rather than feature:reader's own
    // hardcoded version strings (not fixed here, out of scope for this PR).
    implementation("org.readium.kotlin-toolkit:readium-shared:${libs.versions.readium.get()}")
    implementation("org.readium.kotlin-toolkit:readium-streamer:${libs.versions.readium.get()}")
    implementation("org.readium.kotlin-toolkit:readium-navigator:${libs.versions.readium.get()}")
    implementation(libs.androidx.fragment.ktx)
    // Local, screen-scoped ExoPlayer instance for vault audio — deliberately
    // NOT feature:player's shared singleton (PlayerModule.provideExoPlayer),
    // which is wired to PlaybackService/MediaSession/lockscreen controls keyed
    // off a Room itemId that vault content doesn't have. See VaultPlayerViewModel.
    implementation(libs.media3.exoplayer)

    testImplementation(libs.bundles.testing.jvm)
    testImplementation(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.engine)

    // Robolectric for Android-framework-touching code (real android.net.Uri,
    // Compose state) — JUnit4 runs alongside this module's JUnit5 tests via
    // the vintage engine, matching core:vaultcontent.
    testImplementation(libs.bundles.testing.android)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit5.vintage.engine)
    debugImplementation(libs.compose.ui.test.manifest)
}
