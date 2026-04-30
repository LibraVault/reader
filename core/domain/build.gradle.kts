plugins {
    id("libravault.android.library")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.core.domain"
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation("javax.inject:javax.inject:1")

    testImplementation(libs.bundles.testing.jvm)
    testImplementation(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.engine)
}
