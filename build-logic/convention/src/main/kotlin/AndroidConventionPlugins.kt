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
        // The default forked JVM heap (~512m-1g) is insufficient with Hilt/MockK/Coroutines test setup
        // Note: using Java class reference (::class.java) because withType<T>() reified generics
        // don't work in compiled convention plugins — only in .gradle.kts scripts
        //
        // Raised 2048m -> 4096m (issue #700): feature:player's Test task runs the whole
        // module in one forked JVM (no forkEvery/maxParallelForks), and PR #693's new
        // tests (a Robolectric PlaybackServiceTest case + a few plain unit tests) pushed
        // peak heap (Hilt + MockK + Robolectric + Kover instrumentation) over the 2048m
        // ceiling on 2 consecutive CI runs — a real, reproducible OOM, not runner flakiness
        // (dev's own concurrent JVM Tests run succeeded at the same time). Repo-wide since
        // every module shares this convention plugin and other modules were creeping
        // toward the same ceiling.
        //
        // 4096m alone still wasn't enough on real CI (confirmed via the OOM'd test
        // executor's own report — "Caused by: java.lang.OutOfMemoryError: Java heap
        // space" after 3m42s, 731 tasks in, well inside a single JVM run of
        // feature:player's 16 test classes). Root cause: several of those classes
        // are Robolectric+Compose UI tests (PlayerAccessibilityTest,
        // PlayerScreenLandscapeTest) that accumulate significant Shadow/Compose
        // state per class, and with no forkEvery the whole module runs in one
        // never-recycled JVM, so that state just piles up across all 16 classes
        // instead of getting reclaimed. Chasing this with ever-higher maxHeapSize
        // is an arms race that loses every time a module gains another Robolectric
        // test class — forkEvery fixes the actual leak-shaped problem by recycling
        // the worker (and its heap) periodically instead.
        tasks.withType(Test::class.java).configureEach {
            forkEvery = 8
            maxHeapSize = "4096m"
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
