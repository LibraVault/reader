plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.core.domain"
    compileSdk = 34
    defaultConfig { minSdk = 24 }
}

kotlin {
    jvmToolchain(17)

    androidTarget {
        publishLibraryVariants("release")
    }

    iosArm64()
    iosSimulatorArm64()
    iosX64()

    jvm()
}

dependencies {
    // Common dependencies
    commonMainImplementation(libs.coroutines.core)
    commonMainImplementation(libs.kotlinx.serialization.json)

    // Android-specific: Hilt/DI
    androidMainImplementation("javax.inject:javax.inject:1")

    // Testing
    commonTestImplementation(libs.kotlin.test)

    androidTestImplementation(libs.bundles.testing.jvm)
    androidTestImplementation(libs.junit5.engine)
    androidTestRuntimeOnly(libs.junit5.engine)
}
