plugins {
    id("libravault.android.feature")
}

android {
    namespace = "xyz.libravault.feature.reader"
}

dependencies {
    implementation(project(":core:storage"))
    implementation(project(":core:logger"))
    implementation(project(":core:database"))
    implementation(libs.hilt.navigation.compose)
    implementation(libs.navigation.compose)
    implementation(libs.bundles.lifecycle)

    // Readium 3.x — EPUB rendering (BSD 3-Clause, Play Store compatible)
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)

    // AndroidX PDF Viewer — PDF rendering (API 31+, confirmed stable for v1)
    implementation("androidx.pdf:pdf-viewer:1.0.0-alpha04")

    // Fragment interop for hosting Readium EpubNavigatorFragment inside Compose
    implementation("androidx.fragment:fragment-ktx:1.8.1")
    implementation("androidx.fragment:fragment-compose:1.8.1")
}
