plugins {
    id("libravault.android.feature")
}

android {
    namespace = "xyz.libravault.feature.library"
}

dependencies {
    implementation(project(":core:storage"))
    implementation(project(":core:logger"))
    implementation(libs.hilt.navigation.compose)
    implementation(libs.navigation.compose)
    implementation(libs.bundles.lifecycle)
    implementation(libs.coil.compose)
}
