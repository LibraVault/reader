plugins {
    id("libravault.android.library")
    id("libravault.android.compose")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.core.ui"
}

dependencies {
    api(platform(libs.compose.bom))
    api(libs.bundles.compose)

    testImplementation(libs.bundles.testing.jvm)
    testRuntimeOnly(libs.junit5.engine)
}
