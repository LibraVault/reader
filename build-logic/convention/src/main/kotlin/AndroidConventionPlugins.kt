import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("com.android.library")
            apply("org.jetbrains.kotlin.android")
        }
        configure<LibraryExtension> {
            configureAndroidCommon(this)
            defaultConfig.consumerProguardFiles("consumer-rules.pro")
        }
    }
}

class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("libravault.android.library")
            apply("libravault.android.hilt")
            apply("libravault.android.compose")
            apply("de.mannodermaus.android-junit5")
        }
        dependencies {
            add("implementation", project(":core:domain"))
            add("implementation", project(":core:ui"))
            
            // Test dependencies for JVM-based unit tests
            // Using string-based notation since version catalog isn't available in build-logic
            add("testImplementation", "org.junit.jupiter:junit-jupiter-api:5.10.2")
            add("testImplementation", "org.junit.jupiter:junit-jupiter-engine:5.10.2")
            add("testImplementation", "org.junit.jupiter:junit-jupiter-params:5.10.2")
            add("testImplementation", "io.mockk:mockk:1.13.11")
            add("testImplementation", "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
            add("testImplementation", "app.cash.turbine:turbine:1.1.0")
        }
    }
}

class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("com.google.dagger.hilt.android")
            apply("com.google.devtools.ksp")
        }
        dependencies {
            add("implementation", "com.google.dagger:hilt-android:2.52")
            add("ksp", "com.google.dagger:hilt-android-compiler:2.52")
        }
    }
}

class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        configure<com.android.build.gradle.LibraryExtension> {
            buildFeatures.compose = true
        }
    }
}
