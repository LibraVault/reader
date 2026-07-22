plugins {
    id("libravault.android.library")
    id("libravault.android.hilt")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.core.storage"
}

dependencies {
    api(project(":core:domain"))
    api(project(":core:logger"))
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation("androidx.documentfile:documentfile:1.0.1")

    testImplementation(libs.bundles.testing.jvm)
    testRuntimeOnly(libs.junit5.engine)
}
