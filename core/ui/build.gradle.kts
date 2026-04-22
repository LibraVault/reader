plugins {
    id("libravault.android.library")
    id("libravault.android.compose")
}

android {
    namespace = "xyz.libravault.core.ui"
}

dependencies {
    api(platform(libs.compose.bom))
    api(libs.bundles.compose)
}
