plugins {
    id("libravault.android.library")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.core.vaultcontent"
}

dependencies {
    // Content-delivery adapters over Phase 1/2's decrypting reader — see
    // .kilo/plans/1786601688989-encrypted-vaults-implementation-plan.md Phase 3.
    api(project(":core:vaultstore"))

    implementation(libs.readium.shared)
    implementation(libs.media3.datasource)
    implementation(libs.coroutines.core)

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
