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
        // NOTE — 4096m alone still wasn't enough on the next 2 CI runs, but that turned
        // out to be a red herring: it wasn't heap-size-shaped at all. Root-caused (via a
        // local repro + a live jstack dump of the hung executor) to a genuinely-unbounded
        // PlayerViewModel.startProgressSaving() `while (isActive) { delay(...); ... }`
        // coroutine that two new PlayerViewModelTest cases were the first tests in the
        // file to actually start (every other test's play() attempt dies on Uri.parse's
        // real-stub-throws-in-plain-JUnit5 behavior first, so the loop never starts) —
        // left dangling, runTest's own implicit final advanceUntilIdle() drains it
        // forever, spinning at 100% CPU until the heap fills regardless of ceiling. Fixed
        // at the source in PlayerViewModelTest (vm.onCleared() after those two tests), not
        // here — leaving 4096m in place since it's still a legitimate, independently-
        // justified fix for the original #700 OOM.
        tasks.withType(Test::class.java).configureEach {
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
