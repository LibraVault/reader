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
    implementation(libs.androidx.documentfile)

    testImplementation(libs.bundles.testing.jvm)
    testImplementation(libs.kxml2)
    testRuntimeOnly(libs.junit5.engine)
}
