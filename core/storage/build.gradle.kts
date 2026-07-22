plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("libravault.android.hilt")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.core.storage"
    compileSdk = 34
}

kotlin {
    jvmToolchain(17)
    androidTarget {
        publishLibraryVariants("release")
    }
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:domain"))
            api(project(":core:logger"))
            implementation(libs.coroutines.core)
        }

        androidMain.dependencies {
            implementation(libs.coroutines.android)
            implementation("androidx.documentfile:documentfile:1.0.1")
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        androidUnitTest.dependencies {
            implementation(libs.bundles.testing.jvm)
            runtimeOnly(libs.junit5.engine)
        }
    }
}
