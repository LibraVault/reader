plugins {
    id("libravault.android.library")
    id("libravault.android.hilt")
}

android { namespace = "xyz.libravault.core.storage" }

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:logger"))
    implementation(libs.coroutines.android)
}
