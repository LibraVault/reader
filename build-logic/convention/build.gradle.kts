import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "xyz.libravault.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // Plugin artifacts needed at convention plugin compile time.
    // Hardcoded because the root version catalog is not available to
    // included builds in Gradle 9.x without re-declaring it (which
    // triggers a duplicate-from() error). Keep in sync with libs.versions.toml.
    compileOnly("com.android.tools.build:gradle:8.5.0")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.0")
    compileOnly("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.0.0-1.0.21")
    compileOnly("com.google.dagger:hilt-android-gradle-plugin:2.52")
    compileOnly("de.mannodermaus.gradle.plugins:android-junit5:1.10.0.0")
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "libravault.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "libravault.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidFeature") {
            id = "libravault.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("androidHilt") {
            id = "libravault.android.hilt"
            implementationClass = "AndroidHiltConventionPlugin"
        }
        register("androidCompose") {
            id = "libravault.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
    }
}
