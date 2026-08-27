// Macrobenchmark module (issue #695) — https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview
//
// A `com.android.test` module, not `com.android.library`/`com.android.application` —
// distinct AGP module type whose only output is an instrumented test APK that
// drives `:app` from the outside (via UiAutomator) and reads timing data back
// out of the platform (via Perfetto traces), rather than compiling any app code
// of its own. That's also why its instrumented tests live under `src/main/`
// here, not `src/androidTest/` — this whole module *is* the test.
//
// No unit tests, no Kover coverage (see root build.gradle.kts's `name == "benchmark"`
// guard) — nothing here to unit test or cover.
//
// Deliberately NOT wired into any Gradle convention plugin
// (`build-logic/convention`): `com.android.test` is a one-off module type used
// nowhere else in the repo, and forcing it through `AndroidLibraryConventionPlugin`
// (which assumes `com.android.library`'s `LibraryExtension`) would be more
// indirection than the single call site here justifies.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "xyz.libravault.benchmark"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        minSdk = 31
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Points this module's instrumented tests at :app instead of compiling
    // its own app code, and targets the `benchmark` build type added to
    // :app's buildTypes (see app/build.gradle.kts) instead of `release`.
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    // :app's flavorDimensions apply here too — a com.android.test module
    // must declare and pick a target flavor per build. `fdroid` is
    // benchmarked because it needs no Play Billing setup and no signing
    // secrets to build, matching this module's zero-external-dependency goal.
    flavorDimensions += "distribution"
    productFlavors {
        create("fdroid") {
            dimension = "distribution"
            matchingFallbacks += listOf("fdroid")
        }
        create("play") {
            dimension = "distribution"
            matchingFallbacks += listOf("play")
        }
    }

    // No `buildTypes` block here — a `com.android.test` module automatically
    // mirrors its target project's build type names (debug/release/benchmark),
    // it doesn't need its own declared. The `androidComponents.beforeVariants`
    // filter below is what narrows that down to just `benchmark`.

    testOptions {
        managedDevices {
            localDevices {
                // For local/manual runs: `./gradlew :benchmark:pixel6Api34BenchmarkAndroidTest`.
                // CI (Phase 1, issue #695) runs against Firebase Test Lab
                // physical hardware instead — this profile isn't wired into
                // any workflow yet.
                create("pixel6Api34") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp"
                }
            }
        }
    }
}

androidComponents {
    beforeVariants(selector().all()) {
        it.enable = it.buildType == "benchmark"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.uiautomator)
    implementation(libs.benchmark.macro.junit4)
}
