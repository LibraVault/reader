plugins {
    id("libravault.android.library")
}

android {
    namespace = "xyz.libravault.core.domain"
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // Use cases are injected by Hilt — pull in javax.inject only
    implementation("javax.inject:javax.inject:1")
}
