plugins {
    id("libravault.android.library")
    id("libravault.android.hilt")
    alias(libs.plugins.ksp)
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.core.database"
}

// LibravaultDatabase declares `exportSchema = true`, but without this argument
// Room has nowhere to write the schema and silently exports nothing — which is
// why `core/database/schemas/` did not exist despite the annotation claiming it
// should (docs/TEST_COVERAGE_PRD.md, S2).
//
// Two things become possible once these JSONs are committed:
//  - Room validates that a migration's end state matches its codegen, at build
//    time rather than on a user's device during an upgrade.
//  - `MigrationTestHelper` can construct a database at any *exported* version,
//    so migrations added from v7 onward get real coverage cheaply.
//
// It cannot retroactively help with v1..v6: those schemas were never exported,
// and reconstructing them would mean checking out historical revisions. Those
// migrations are covered instead by MigrationChainTest, which runs the whole
// v1 -> v7 chain against real SQLite.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.bundles.room)
    ksp(libs.room.compiler)
    implementation(libs.coroutines.android)

    testImplementation(libs.bundles.testing.jvm)
    testRuntimeOnly(libs.junit5.engine)

    // Robolectric — real SQLite execution for MIGRATION_6_7RealExecutionTest, which
    // opens an actual Room database through the real migration path rather than
    // mocking SupportSQLiteDatabase and checking SQL strings look right (as
    // MigrationsTest.kt's existing coverage does for MIGRATION_4_5). A schema-shape
    // mistake in a rebuild-the-table migration like MIGRATION_6_7 throws at runtime
    // on every user's device on upgrade; Robolectric is the only way in this repo to
    // catch that before it ships, on the JVM, no emulator. JUnit4 (Robolectric's
    // runner requirement) runs alongside this module's JUnit5 tests via the vintage
    // engine, mirroring :feature:library's/:feature:reader's setup.
    testImplementation(libs.robolectric)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit5.vintage.engine)
}
