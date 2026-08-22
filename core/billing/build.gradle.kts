plugins {
    id("libravault.android.library")
    id("libravault.android.hilt")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.core.billing"

    // Mirrors the (since-deleted, see PR #172) core:licensing module's shape:
    // shared interface in src/main, real Play Billing impl in src/play,
    // dependency-free no-op in src/fdroid.
    flavorDimensions += "distribution"
    productFlavors {
        create("fdroid") { dimension = "distribution" }
        create("play")   { dimension = "distribution" }
    }
}

dependencies {
    implementation(project(":core:storage")) // SupporterRepository
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // billing-ktx pulls in the full Play Billing client library — scoped to the
    // play source set only so it never leaks into the fdroid build (F-Droid must
    // stay dependency-free/offline).
    "playImplementation"(libs.billing.ktx)

    testImplementation(libs.bundles.testing.jvm)
    testRuntimeOnly(libs.junit5.engine)
}
