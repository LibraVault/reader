import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
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
            // Note: de.mannodermaus.android-junit5 must be applied in the module's build.gradle.kts
            // plugins block, not here, because it needs to be resolved by the target project
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

        // Configure test worker JVM heap to prevent OutOfMemoryError in Gradle Test Executor
        // The default forked JVM heap (~512m-1g) is insufficient with Hilt/MockK/Coroutines test setup.
        // Was 2048m; raised to 3072m after feature:player's testDebugUnitTest OOM'd twice in a row
        // in CI (Gradle Test Executor heap exhaustion, not an assertion failure) once its suite grew
        // by a few Robolectric/Hilt tests — see issue #700. Applies to every feature:* module, not
        // just feature:player, since other modules were already creeping toward the same ceiling.
        // Note: using Java class reference (::class.java) because withType<T>() reified generics
        // don't work in compiled convention plugins — only in .gradle.kts scripts
        tasks.withType(Test::class.java).configureEach {
            maxHeapSize = "3072m"
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
            // Hardcoded for the same reason as build-logic/convention/build.gradle.kts's
            // own compileOnly deps — keep in sync with libs.versions.toml's `hilt` version.
            add("implementation", "com.google.dagger:hilt-android:2.58")
            add("ksp", "com.google.dagger:hilt-android-compiler:2.58")
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
