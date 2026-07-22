plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("libravault.android.hilt")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.core.logger"
    compileSdk = 34
    defaultConfig { minSdk = 24 }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)

    androidTarget {
        publishLibraryVariants("release")
    }

    iosArm64()
    iosSimulatorArm64()
    iosX64()
}

dependencies {
    commonMainImplementation(libs.coroutines.core)
    androidMainImplementation(libs.coroutines.android)

    commonTestImplementation(libs.kotlin.test)

    androidTestImplementation(libs.bundles.testing.jvm)
    androidTestRuntimeOnly(libs.junit5.engine)
}
