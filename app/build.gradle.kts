import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.util.Properties

plugins {
    id("libravault.android.application")
    id("libravault.android.hilt")
    id("de.mannodermaus.android-junit5")
    alias(libs.plugins.androidx.baselineprofile)
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

    // ── Distribution flavours ─────────────────────────────────────────────────
    //
    //   ./gradlew assembleFdroidDebug    → F-Droid / direct-download build
    //   ./gradlew assemblePlayDebug      → Play Store build (Play Billing, v2)
    //
    // F-Droid flavour: activation via Ed25519 license key, no in-app payment links.
    // Play flavour:    Google Play one-tap purchase (Play Billing, to be wired in v2).
    flavorDimensions += "distribution"
    productFlavors {
        create("fdroid") {
            dimension = "distribution"
        }
        create("play") {
            dimension = "distribution"
        }
    }

    lint {
        baseline = file("lint-baseline.xml")
        checkReleaseBuilds = false  // Release build lint baseline can be added later
    }

    defaultConfig {
        applicationId = "xyz.libravault.app"
        // versionCode defaults to this literal for local/F-Droid builds (F-Droid's
        // reproducible build must match what's committed here). CI test-distribution
        // builds (android-firebase-distribution.yml) override it per-run via
        // -PlibravaultVersionCode so consecutive uploads get distinct build numbers —
        // without an override, every Firebase App Distribution build shared this same
        // "12" regardless of when it was actually built (issue #655).
        versionCode = (project.findProperty("libravaultVersionCode") as String?)?.toIntOrNull() ?: 12
        versionName = "0.4.6.1-alpha"
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

        // `androidx.baselineprofile` (applied below) auto-derives a
        // "benchmarkRelease" and a "nonMinifiedRelease" build type from
        // `release` for :benchmark/:baselineprofile to target — but a
        // silent auto-derivation inherits `release`'s own signingConfig
        // too, which needs the real production keystore. Confirmed live
        // (issue #695/#707 follow-up): `./gradlew :app:assembleFdroidBenchmarkRelease`
        // and `:app:assembleFdroidNonMinifiedRelease` both fail identically
        // without `keystore.properties` — "SigningConfig 'release' is
        // missing required property 'storeFile'" — meaning neither
        // `:benchmark:connectedFdroidBenchmarkAndroidTest` nor
        // `:baselineprofile:generateBaselineProfile` can actually run on a
        // machine without the production keystore, contrary to what both
        // modules' own comments/docs claimed. Declaring these two build
        // types explicitly (rather than leaving them to the plugin's
        // silent default) overrides that: the plugin detects an
        // already-declared build type of the name it would otherwise
        // create and uses this declaration instead of its own.
        create("benchmarkRelease") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            // :app's own library-module dependencies (core:*, feature:*)
            // only declare debug/release — matches this repo's existing
            // :benchmark/:baselineprofile matchingFallbacks convention.
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
        create("nonMinifiedRelease") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
            // "non-minified" — Baseline Profile generation needs real
            // (un-obfuscated) method/class names to produce a profile that
            // actually matches what R8 later optimizes in the real release
            // build; that's the whole reason this build type exists
            // separately from "release" and "benchmarkRelease".
            isMinifyEnabled = false
            isShrinkResources = false
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
                // BouncyCastle META-INF entries that collide during APK packaging
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/INDEX.LIST",
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

    // ── APK output naming ─────────────────────────────────────────────────────
    // Embed the current git branch in the APK filename so builds from different
    // branches don't overwrite each other.
    // e.g. feature/pro-upgrade + fdroidDebug → libravault-feature-pro-upgrade-fdroid-debug.apk
    val gitBranch = run {
        val abbrev = providers.exec {
            commandLine("git", "rev-parse", "--abbrev-ref", "HEAD")
        }.standardOutput.asText.get().trim()
        if (abbrev != "HEAD") {
            abbrev
        } else {
            // Detached HEAD (CI tag build) — use the tag name, fall back to short SHA
            runCatching {
                providers.exec {
                    commandLine("git", "describe", "--tags", "--exact-match", "HEAD")
                }.standardOutput.asText.get().trim()
            }.getOrElse {
                providers.exec {
                    commandLine("git", "rev-parse", "--short", "HEAD")
                }.standardOutput.asText.get().trim()
            }
        }
    }.replace("/", "-").replace("_", "-")

    applicationVariants.all {
        val kebabName = name
            .replace(Regex("(?<=[a-z])(?=[A-Z])"), "-")
            .lowercase()   // fdroidDebug → fdroid-debug
        outputs.all {
            (this as BaseVariantOutputImpl).outputFileName =
                "libravault-$gitBranch-$kebabName.apk"
        }
    }
}

// Baseline Profile — generated by :baselineprofile (see issue #695), consumed
// here and packaged into src/release/generated/baselineProfiles/. No numbers
// are committed yet: generation needs a physical device/emulator, which this
// repo's CI does not have wired up (tracked as issue #695's Phase 1
// follow-up) — run `./gradlew :baselineprofile:generateBaselineProfile`
// locally on real hardware to produce and commit the first one.
baselineProfile {
    // Only the fdroid flavour is profiled — see benchmark/build.gradle.kts.
    filter { include("xyz.libravault.**") }
}

dependencies {
    baselineProfile(project(":baselineprofile"))
    implementation(libs.androidx.profileinstaller)

    // Feature modules
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:library"))
    implementation(project(":feature:reader"))
    implementation(project(":feature:player"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:vault"))

    // Core modules
    implementation(project(":core:database"))
    implementation(project(":core:storage"))
    implementation(project(":core:ui"))
    implementation(project(":core:logger"))

    // Splash screen API (Android 12+ native + backport to API 23)
    implementation(libs.androidx.splashscreen)
    // Material3 for system-level theming (SAF picker, status/nav bars)
    implementation(libs.material)

    // Navigation host lives in app
    implementation(libs.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.lifecycle)
    // FragmentActivity needed by the EPUB reader (Readium uses supportFragmentManager)
    implementation(libs.androidx.fragment.ktx)

    // Compose tooling (debug only)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.bundles.testing.jvm)
    testRuntimeOnly(libs.junit5.engine)
    // Robolectric (JUnit4-only) — ManifestPermissionsTest reads the real merged
    // manifest. Runs alongside this module's JUnit5 tests via the vintage engine,
    // same pattern as :feature:library / :feature:settings.
    testImplementation(libs.bundles.testing.android)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit5.vintage.engine)
}
