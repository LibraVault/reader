plugins {
    id("libravault.android.feature")
}

android {
    namespace = "xyz.libravault.feature.reader"
}

dependencies {
    implementation(project(":core:storage"))
    implementation(libs.hilt.navigation.compose)
    implementation(libs.navigation.compose)
    implementation(libs.bundles.lifecycle)
    // M2: Readium deps added here
    // implementation(libs.readium.shared)
    // implementation(libs.readium.streamer)
    // implementation(libs.readium.navigator)
}
