plugins {
    id("libravault.android.library")
    id("libravault.android.hilt")
}

android {
    namespace = "xyz.libravault.core.logger"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
