plugins {
    id("libravault.android.library")
    id("libravault.android.hilt")
    alias(libs.plugins.ksp)
}

android {
    namespace = "xyz.libravault.core.database"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.bundles.room)
    ksp(libs.room.compiler)
    implementation(libs.coroutines.android)
}
