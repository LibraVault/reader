pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("de.mannodermaus.android-junit5") version "1.10.0.0"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
        maven("https://jitpack.io")
        // sherpa-onnx prebuilt AAR (core:tts) - a direct `files(...)` dependency on this .aar
        // isn't allowed when the consuming module itself produces an AAR, so it's resolved
        // as a regular flatDir dependency instead (see core/tts/build.gradle.kts).
        flatDir {
            dirs("${rootDir}/third-party/sherpa-onnx")
        }
    }
}

rootProject.name = "libravault"

include(":app")

// Core modules
include(":core:database")
include(":core:storage")
include(":core:domain")
include(":core:vaultcrypto")
include(":core:vaultstore")
include(":core:vaultcontent")
include(":core:ui")
include(":core:logger")
include(":core:tts")
include(":core:billing")
include(":core:cloudtts")

// Feature modules
include(":feature:onboarding")
include(":feature:library")
include(":feature:reader")
include(":feature:player")
include(":feature:settings")
include(":feature:vault")

// Macrobenchmark (issue #695) — startup/frame-timing regression baseline
include(":benchmark")
