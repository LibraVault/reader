import java.util.Properties

plugins {
    id("libravault.android.application")
    id("libravault.android.hilt")
}

// ── Signing ───────────────────────────────────────────────────────────────────
// keystore.properties is gitignored — copy keystore.properties.template to get started.
// In CI, signing is handled by the release.yml workflow via GitHub Secrets.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "xyz.libravault.app"

    defaultConfig {
        applicationId = "xyz.libravault.app"
        versionCode   = 1
        versionName   = "0.1.0"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile     = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias      = keystoreProperties["keyAlias"] as String
                keyPassword   = keystoreProperties["keyPassword"] as String
            }
            // In CI, signing params are injected via -P flags in release.yml
        }
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
            signingConfig      = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // ── Reproducible builds (required for F-Droid) ────────────────────────────
    // Strips timestamps and ordering non-determinism from the APK so that
    // F-Droid's build servers can verify the binary matches the source.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
            )
        }
    }
}

dependencies {
    // Feature modules
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:library"))
    implementation(project(":feature:reader"))
    implementation(project(":feature:player"))
    implementation(project(":feature:settings"))

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
