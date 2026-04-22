plugins {
    id("libravault.android.feature")
}

android {
    namespace = "xyz.libravault.feature.player"
}

dependencies {
    implementation(project(":core:storage"))
    implementation(libs.hilt.navigation.compose)
    implementation(libs.navigation.compose)
    implementation(libs.bundles.lifecycle)
    // M3: Media3 deps
    // implementation(libs.bundles.media3)
}
