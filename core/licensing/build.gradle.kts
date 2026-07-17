plugins {
    id("libravault.android.library")
    id("libravault.android.hilt")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.core.licensing"

    flavorDimensions += "distribution"
    productFlavors {
        create("fdroid") { dimension = "distribution" }
        create("play")   { dimension = "distribution" }
    }

    packaging {
        resources {
            // BouncyCastle ships its own META-INF entries that conflict during
            // APK packaging when multiple modules include it.
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/INDEX.LIST",
            )
        }
    }
}

dependencies {
    // Encrypted local storage for unlock state
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Ed25519 signature verification (offline, no network)
    implementation(libs.bouncycastle.bcprov)

    // Google Play Billing — play flavor only; excluded from F-Droid to prevent
    // google.android.datatransport (Google's telemetry layer) from entering the APK
    "playImplementation"("com.android.billingclient:billing-ktx:7.1.1")

    // Coroutines for KeyProGate.activateWithKey / ProStateManager flows.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation(libs.bundles.testing.jvm)
    testRuntimeOnly(libs.junit5.engine)
}
