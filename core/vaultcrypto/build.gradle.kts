plugins {
    id("libravault.android.library")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.core.vaultcrypto"
}

dependencies {
    // Pure-Kotlin crypto core for Encrypted Vaults — deliberately zero Android
    // dependencies in the source itself (no android.* imports), so it is fully
    // JVM-testable without Robolectric or a device. Platform key storage
    // (Android Keystore) is layered on top in core:vaultstore (Phase 2), not here.
    // See .kilo/plans/1786601688989-encrypted-vaults-implementation-plan.md Phase 1.
    implementation(libs.coroutines.core)

    // Argon2id KDF — re-added here as a fresh, deliberate dependency (PRD §8.4).
    // NOT inherited from core:licensing: that module's BouncyCastle usage is being
    // deleted along with KeyProGate (PRD §10.1), so this is a new decision, not a
    // continuation of the old one.
    implementation(libs.bouncycastle.bcprov)

    testImplementation(libs.bundles.testing.jvm)
    testImplementation(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.engine)
}
