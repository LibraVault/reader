plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("libravault.android.hilt")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "xyz.libravault.core.licensing"
    compileSdk = 34

    flavorDimensions += "distribution"
    productFlavors {
        create("fdroid") { dimension = "distribution" }
        create("play")   { dimension = "distribution" }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/INDEX.LIST",
            )
        }
    }
}

kotlin {
    jvmToolchain(17)
    androidTarget {
        publishLibraryVariants("release")
    }
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:domain"))
            implementation(libs.coroutines.core)
        }

        androidMain.dependencies {
            implementation("androidx.security:security-crypto:1.1.0-alpha06")
            implementation(libs.bouncycastle.bcprov)
            implementation(libs.coroutines.android)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        androidUnitTest.dependencies {
            implementation(libs.bundles.testing.jvm)
            runtimeOnly(libs.junit5.engine)
        }
    }
}

dependencies {
    "playImplementation"("com.android.billingclient:billing-ktx:7.1.1")
}
