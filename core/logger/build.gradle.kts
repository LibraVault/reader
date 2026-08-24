plugins {
    id("libravault.android.library")
    id("libravault.android.hilt")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.core.logger"

    // Needed for BuildConfig.DEBUG, which gates the Logcat sink in
    // LibravaultLogger.write() — see issue #528. The `app` module disables
    // BuildConfig entirely for reproducible builds (app/build.gradle.kts),
    // but that's a separate, per-module generated class; DEBUG is a plain
    // build-type boolean, not one of the host/time-varying fields that
    // reproducibility guard is about, so enabling it here for this module
    // only doesn't reintroduce that problem.
    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation(libs.bundles.testing.jvm)
    testRuntimeOnly(libs.junit5.engine)
}
