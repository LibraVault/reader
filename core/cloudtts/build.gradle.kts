plugins {
    id("libravault.android.library")
    id("libravault.android.hilt")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.core.cloudtts"

    // Mirrors core:billing's shape (see core/billing/build.gradle.kts), with
    // one deliberate difference: only the vendor HTTP adapters (src/play) and
    // their NoOp counterpart (src/fdroid) are flavor-split — that's the actual
    // networking surface PRD §5/§8 requires isolating from F-Droid. The gate,
    // consent, and secure key-storage code all live in src/main, unflavored:
    // Android Keystore isn't a networking dependency, so there's no F-Droid
    // reason to duplicate it per flavor (it's simply unreachable on F-Droid,
    // since NoOpBillingClient makes the subscription half of the gate always
    // false there — see CloudTtsGate.kt).
    flavorDimensions += "distribution"
    productFlavors {
        create("fdroid") { dimension = "distribution" }
        create("play")   { dimension = "distribution" }
    }
}

dependencies {
    implementation(project(":core:billing"))    // SupportBillingClient — real gate signal (a), PRD §4
    implementation(project(":core:tts"))         // TtsPreferences — consent flag + engine seam
    implementation(project(":core:vaultstore"))  // HardwareKeyWrapFactory — see CloudApiKeyStore.kt
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    // Credentials are a Map<String, String> (most vendors: one "api_key" field;
    // Amazon Polly needs access_key_id + secret_access_key + region — see
    // CloudApiKeyStore.kt) — serialized to JSON before hardware-key wrapping.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.bundles.testing.jvm)
    testRuntimeOnly(libs.junit5.engine)
}
