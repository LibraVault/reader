plugins {
    id("libravault.android.library")
    id("libravault.android.hilt")
}

android {
    namespace = "xyz.libravault.core.logger"
}

dependencies {
    implementation(libs.coroutines.android)
}
