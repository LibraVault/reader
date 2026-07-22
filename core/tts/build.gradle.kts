plugins {
    id("libravault.kmp.library")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.core.tts"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
        }

        androidMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
            implementation(libs.hilt.android)
        }

        androidUnitTest.dependencies {
            implementation(libs.bundles.testing.jvm)
            implementation(libs.junit5.engine)
            runtimeOnly(libs.junit5.engine)
        }
    }
}
