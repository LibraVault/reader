plugins {
    id("libravault.android.application")
    id("libravault.android.hilt")
}

android {
    namespace = "xyz.libravault.app"

    defaultConfig {
        applicationId = "xyz.libravault.app"
        versionCode   = 1
        versionName   = "0.1.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix   = "-debug"
            isDebuggable        = true
        }
        release {
            isMinifyEnabled    = true
            isShrinkResources  = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // signing config added when release keystore is set up
        }
    }
}

dependencies {
    // Feature modules
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:library"))
    implementation(project(":feature:reader"))
    implementation(project(":feature:player"))

    // Core modules
    implementation(project(":core:database"))
    implementation(project(":core:storage"))
    implementation(project(":core:ui"))
    implementation(project(":core:logger"))

    // Navigation host lives in app
    implementation(libs.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.lifecycle)

    // Compose tooling (debug only)
    debugImplementation(libs.compose.ui.tooling)
}
