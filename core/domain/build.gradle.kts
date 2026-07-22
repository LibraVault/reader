plugins {
    id("libravault.kmp.library")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.core.domain"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }

        androidMain.dependencies {
            implementation("javax.inject:javax.inject:1")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        androidUnitTest.dependencies {
            implementation(libs.bundles.testing.jvm)
            implementation(libs.junit5.engine)
            runtimeOnly(libs.junit5.engine)
        }
    }
}
