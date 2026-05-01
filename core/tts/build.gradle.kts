plugins {
    id("libravault.android.library")
    id("libravault.android.hilt")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.core.tts"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation(libs.bundles.testing.jvm)
    testRuntimeOnly(libs.junit5.engine)
}
