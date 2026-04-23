plugins {
    id("libravault.android.feature")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.feature.reader"
}

dependencies {
    implementation(project(":core:storage"))
    implementation(project(":core:logger"))
    implementation(project(":core:database"))
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")

    // Readium 3.x — EPUB rendering (BSD 3-Clause, Play Store compatible)
    implementation("org.readium.kotlin-toolkit:readium-shared:3.0.0-beta.2")
    implementation("org.readium.kotlin-toolkit:readium-streamer:3.0.0-beta.2")
    implementation("org.readium.kotlin-toolkit:readium-navigator:3.0.0-beta.2")

    // AndroidX PDF Viewer — PDF rendering (API 31+, confirmed stable for v1)
    implementation("androidx.pdf:pdf-viewer:1.0.0-alpha04")

    // Fragment interop for hosting Readium EpubNavigatorFragment inside Compose
    implementation("androidx.fragment:fragment-ktx:1.8.1")
    implementation("androidx.fragment:fragment-compose:1.8.1")
}
