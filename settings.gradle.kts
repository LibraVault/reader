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
    }
}

rootProject.name = "libravault"

include(":app")

// Core modules
include(":core:database")
include(":core:storage")
include(":core:domain")
include(":core:ui")
include(":core:logger")

// Feature modules
include(":feature:onboarding")
include(":feature:library")
include(":feature:reader")
include(":feature:player")
include(":feature:settings")
