plugins {
    id("libravault.android.feature")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.feature.player"
}

dependencies {
    implementation(project(":core:storage"))
    implementation(project(":core:logger"))
    implementation(project(":core:database"))
    implementation(libs.hilt.navigation.compose)
    implementation(libs.navigation.compose)
    implementation(libs.bundles.lifecycle)
    implementation(libs.coil.compose)

    // Media3 — ExoPlayer, MediaSession, UI
    implementation(libs.bundles.media3)

    // Guava for ListenableFuture (MediaController.buildAsync)
    implementation("com.google.guava:guava:33.2.1-android")
}
