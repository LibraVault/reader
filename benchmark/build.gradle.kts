plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "xyz.libravault.benchmark"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Measured against the fdroid flavour: it is the primary distribution
    // channel and needs no Play Billing setup to assemble. The play flavour
    // is not separately benchmarked; revisit if the two diverge meaningfully
    // in startup-critical code paths.
    flavorDimensions += "distribution"
    productFlavors {
        create("fdroid") {
            dimension = "distribution"
        }
        create("play") {
            dimension = "distribution"
        }
    }

    // Benchmarks run against a release-shaped build (minified, non-debuggable)
    // but debug-signed so this module never needs the real release keystore.
    // `matchingFallbacks` targets :app's explicit "benchmarkRelease" build
    // type (see app/build.gradle.kts) — NOT "release" itself. Confirmed live
    // (issue #695/#707 follow-up) that falling back to "release" resolves
    // :app's genuinely-signed production variant, which fails to package
    // without the real release keystore ("SigningConfig 'release' is
    // missing required property 'storeFile'"). "benchmarkRelease" is
    // release-shaped (minified, non-debuggable) but explicitly debug-signed
    // for exactly this reason.
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("benchmarkRelease")
        }
    }

    targetProjectPath = ":app"
    // Required for macrobenchmark test modules: lets the test APK instrument
    // itself instead of requiring a separate target instrumentation manifest
    // entry on :app. See androidx Macrobenchmark setup docs.
    @Suppress("UnstableApiUsage")
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}
