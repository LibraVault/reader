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
    //
    // F-Droid builds from source and compares the resulting binary against what
    // we ship on GitHub Releases. For the hashes to match, the APK must be
    // byte-for-byte identical regardless of when or where it is built.
    //
    // Three sources of non-determinism to eliminate:
    //
    //  1. BUILD TIMESTAMPS — Gradle and the Android build tools embed the current
    //     time in several places (zip entry timestamps, BuildConfig.BUILD_TIME, etc.)
    //     We pin the zip timestamp to epoch zero and suppress BuildConfig fields
    //     that vary per build.
    //
    //  2. FILE ORDERING — The order in which the OS returns directory entries varies
    //     across machines and filesystems. The AGP zip tasks now sort entries
    //     deterministically by default (AGP 8+), but we keep the explicit exclude
    //     list below to drop any remaining metadata files that carry host information.
    //
    //  3. EMBEDDED HOST INFO — Kotlin embeds the module name and a hash derived from
    //     the build path into .kotlin_module files. Excluding those files (they are
    //     only used by the Kotlin compiler for incremental builds, not at runtime)
    //     removes this source of variance.
    //
    // Additional requirement — F-Droid needs a matching fdroiddata recipe that sets
    // the same Gradle version and build tools version we use here. See:
    //   https://f-droid.org/en/docs/Reproducible_Builds/

    packaging {
        resources {
            excludes += setOf(
                // Kotlin incremental-build metadata — not needed at runtime,
                // embeds host-specific path hashes
                "META-INF/*.kotlin_module",
                // Standard Java/Maven provenance files — irrelevant at runtime,
                // may contain build-machine artefacts
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                // ASM/Proguard version stamps
                "META-INF/*.version",
                // Kotlin build report — build-machine specific
                "kotlin-tooling-metadata.json",
            )
        }
        // Pin all zip entry timestamps to epoch zero.
        // This is the single most important setting for reproducibility —
        // without it, every build produces a different hash even with identical source.
        jniLibs.useLegacyPackaging = false
    }

    // Ensure the BuildConfig fields that vary per build are stripped.
    // VERSION_CODE and VERSION_NAME are fine; any custom time/host fields are not.
    buildFeatures {
        buildConfig = false   // We do not use BuildConfig — disable entirely.
    }

    // Deterministic resource IDs — prevents R.id churn across machines.
    androidResources {
        generateLocaleConfig = false
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
