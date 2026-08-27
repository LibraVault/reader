// Top-level build file — configuration here applies to all sub-projects.
plugins {
    alias(libs.plugins.android.application)  apply false
    alias(libs.plugins.android.library)      apply false
    alias(libs.plugins.android.test)         apply false
    alias(libs.plugins.kotlin.android)       apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler)     apply false
    alias(libs.plugins.ksp)                  apply false
    alias(libs.plugins.hilt)                 apply false
    alias(libs.plugins.kover)                apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
}

subprojects {
    tasks.withType<Test>().configureEach {
        maxParallelForks = 2
        setForkEvery(100)
        maxHeapSize = "1g"
    }

    // Release-variant unit tests are broken by construction: every module
    // declares `compose.ui.test.manifest` (needed by createComposeRule()) as
    // debugImplementation only, so Robolectric Compose tests can never pass
    // under a release variant (e.g. testReleaseUnitTest, testPlayReleaseUnitTest).
    // CI only ever runs the debug variant, so this brings `./gradlew test` in
    // line with what's actually gated instead of leaving a trap for anyone
    // following TEST_PLAN.md. See issue #233.
    tasks.matching { it.name.matches(Regex("^test\\w*ReleaseUnitTest$")) }.configureEach {
        enabled = false
    }
}

// ── Coverage (docs/TEST_COVERAGE_PRD.md Phase 1) ──────────────────────────────
//
// Before this the repo had no coverage instrumentation on either platform,
// which is how TEST_PLAN.md came to publish hand-estimated per-module
// percentages nobody could check (core:domain was listed at "95%"; it measures
// 17.2%).
//
// Kover is applied here rather than from build-logic/convention because the
// convention plugins cannot see the version catalog — they hardcode versions,
// see AndroidConventionPlugins.kt — and adding another duplicated version
// constant there is exactly the trap the toolchain notes warn about.
//
// DEBUG VARIANT ONLY, deliberately. There is no root aggregate task and no
// `kover(project(...))` wiring, because Kover's cross-variant "total" report
// pulls in `test<Flavor>ReleaseUnitTest`, and release unit tests cannot pass in
// this repo by construction: `createComposeRule()` needs an Activity supplied
// by androidx.compose.ui:ui-test-manifest, which every module declares as
// `debugImplementation`. Running the total report surfaced 7 pre-existing
// failures in :core:ui that CI had never seen, purely from the release variant.
// Measuring the variant we actually test is both correct and half the cost.
//
// Reports (per module, debug variant):
//   ./gradlew koverXmlReportDebug              -> <module>/build/reports/kover/reportDebug.xml
//   ./gradlew koverHtmlReportDebug             -> <module>/build/reports/kover/htmlDebug/index.html
//   python3 scripts/coverage-summary.py        -> merged per-module Markdown table
//
// Note the same task-name trap jvm-tests.yml documents for testDebugUnitTest:
// :app and :feature:settings have flavorDimensions, so their tasks are
// koverXmlReport{Fdroid,Play}Debug — a bare `koverXmlReportDebug` silently
// skips them. scripts/coverage-summary.py fails loudly if a module's report is
// missing rather than quietly reporting a smaller repo.
subprojects {
    // `:core` and `:feature` are container projects created implicitly by the
    // `include(":core:database")` entries in settings.gradle.kts — no build
    // file, no sources, nothing to instrument.
    if (!buildFile.exists()) return@subprojects

    apply(plugin = "org.jetbrains.kotlinx.kover")

    extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
        reports {
            filters {
                excludes {
                    // Generated, DI, and pure-declaration code cannot be
                    // meaningfully covered by unit tests, and leaving it in
                    // makes every module read artificially low — which is how
                    // coverage numbers stop being trusted and start being
                    // ignored.
                    classes(
                        "*_Factory",
                        "*_Factory\$*",
                        "*_HiltModules*",
                        "*_MembersInjector",
                        "*_Impl",            // Room-generated DAO/database impls
                        "*_Impl\$*",
                        "Hilt_*",
                        "*ComposableSingletons*",
                        "*\$\$serializer",
                        "dagger.hilt.*",
                        "hilt_aggregated_deps.*",
                        "*.BuildConfig",
                        "*.databinding.*",
                        "*.R",
                        "*.R\$*",
                    )
                    // @Preview only — deliberately NOT @Composable. Excluding
                    // all composables would hide the single largest untested
                    // surface in the repo (LibraryScreen.kt, 1,344 LOC) from
                    // the metric whose entire job is to expose it, and would
                    // stop the 15 Robolectric Compose tests in src/test/ from
                    // counting for anything. Previews never ship.
                    annotatedBy("androidx.compose.ui.tooling.preview.Preview")
                }
            }
        }
    }
}
