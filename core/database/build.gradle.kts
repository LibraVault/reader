plugins {
    id("libravault.android.library")
    id("libravault.android.hilt")
    alias(libs.plugins.ksp)
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.core.database"
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
