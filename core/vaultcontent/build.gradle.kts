plugins {
    id("libravault.android.library")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.core.vaultcontent"

    // readium-shared requires core library desugaring — AGP's
    // checkDebugAndroidTestAarMetadata enforces this on the androidTest
    // variant too (issue #253: connectedDebugAndroidTest failed here the
    // first time ui-tests.yml actually ran to completion on a `dev` PR,
    // unrelated to this module having any androidTest sources of its own).
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    // Content-delivery adapters over Phase 1/2's decrypting reader — see
    // .kilo/plans/1786601688989-encrypted-vaults-implementation-plan.md Phase 3.
    api(project(":core:vaultstore"))

    implementation(libs.readium.shared)
    implementation(libs.media3.datasource)
    implementation(libs.coroutines.core)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.bundles.testing.jvm)
    testImplementation(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.engine)

    // VaultDataSourceTest needs a real android.net.Uri, which throws "not mocked"
    // under AGP's default stub android.jar — Robolectric, same pattern as
    // core:ui's LibravaultThemeTest.
    testImplementation(libs.robolectric)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit5.vintage.engine)
}
