plugins {
    id("libravault.android.library")
    id("libravault.android.hilt")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.core.vaultstore"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Robolectric-hosted tests (VaultSessionManagerTest touches ProcessLifecycleOwner,
    // a real Android framework class) — same setup as core:vaultcontent's Phase 3 tests.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // First androidTest APK this module has ever packaged — needed to resolve a
    // META-INF collision between BouncyCastle and jspecify that only surfaces when
    // the androidTest variant is actually built (issue #253). :app already excludes
    // this exact path for its own APK, but library modules inherit nothing from it.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/INDEX.LIST",
            )
        }
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

    // ── Instrumentation tests ──
    // AndroidKeystoreHardwareKeyWrapTest — the real AndroidKeyStore path (issue
    // #253). Robolectric's Keystore shim has no securityLevel/StrongBox/
    // KeyInfo modelling, so this can only be verified on a real device/emulator.
    androidTestImplementation(libs.bundles.testing.instrumentation)
}
