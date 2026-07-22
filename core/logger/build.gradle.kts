plugins {
    id("libravault.android.library")
    id("libravault.android.hilt")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.core.logger"

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    testImplementation(libs.bundles.testing.jvm)
    testRuntimeOnly(libs.junit5.engine)
}
