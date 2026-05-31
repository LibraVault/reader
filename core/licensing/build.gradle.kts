plugins {
    id("libravault.android.library")
    id("libravault.android.hilt")
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
    implementation("org.bouncycastle:bcprov-jdk18on:1.78")

    // Google Play Billing — play flavor only; excluded from F-Droid to prevent
    // google.android.datatransport (Google's telemetry layer) from entering the APK
    "playImplementation"("com.android.billingclient:billing-ktx:7.1.1")

    // Recovery endpoint client — the only network component in the app
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
