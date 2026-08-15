plugins {
    id("libravault.android.library")
    id("libravault.android.hilt")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.core.vaultstore"

    // Robolectric-hosted tests (VaultSessionManagerTest touches ProcessLifecycleOwner,
    // a real Android framework class) — same setup as core:vaultcontent's Phase 3 tests.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":core:vaultcrypto"))
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // ProcessLifecycleOwner — VaultSessionManager auto-locks every open vault
    // the moment the whole app backgrounds, not just one Activity.
    implementation(libs.androidx.lifecycle.process)

    testImplementation(libs.bundles.testing.jvm)
    testImplementation(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.engine)
    // Robolectric for VaultSessionManagerTest — JUnit4 runs alongside this
    // module's JUnit5 tests via the vintage engine, matching core:vaultcontent.
    testImplementation(libs.bundles.testing.android)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit5.vintage.engine)
}
