plugins {
    id("libravault.kmp.library")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.core.logger"

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
        }

        androidMain.dependencies {
            implementation(libs.coroutines.android)
            implementation(libs.hilt.android)
        }

        androidUnitTest.dependencies {
            implementation(libs.bundles.testing.jvm)
            implementation(libs.junit5.engine)
            runtimeOnly(libs.junit5.engine)
        }
    }
}
