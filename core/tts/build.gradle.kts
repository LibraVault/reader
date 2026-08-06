plugins {
    id("libravault.android.library")
    id("libravault.android.hilt")
    id("de.mannodermaus.android-junit5")
}

// sherpa-onnx AAR reference
val sherpaOnnxAar = file("${rootProject.projectDir}/third-party/sherpa-onnx/sherpa-onnx-android.aar")
if (!sherpaOnnxAar.exists()) {
    logger.warn("⚠️  sherpa-onnx AAR not found at ${sherpaOnnxAar.absolutePath}")
    logger.warn("     Run: ./third-party/sherpa-onnx/build-aar.sh to build it")
}

android {
    namespace = "xyz.libravault.core.tts"

    buildFeatures {
        buildConfig = true
    }

    // Pocket TTS bundles exactly one voice: a Piper VITS model trained from
    // scratch on the public-domain LJSpeech dataset. This was chosen over
    // sherpa-onnx's own "Pocket TTS" model family (CC-BY-NC, non-commercial
    // use only) and over other Piper voices like lessac/amy (finetuned from a
    // Blizzard-2013-licensed voice whose license also forbids commercial use).
    // Picking a permissively-licensed voice avoids ever having to revisit
    // this once LibraVault's Pro-tier/licensing infrastructure (already in
    // the codebase, not yet launched) goes live. See SHERPA_ONNX_SETUP.md.
    //
    // The model itself is bundled into assets/pocket-tts-model/ at dev/
    // release-prep time by third-party/sherpa-onnx/setup-android-model.sh
    // (same URL/checksum as iOS's setup-ios.sh - not re-read from here).
    // This SHA256 is only kept as the on-disk "which model version is this"
    // marker PocketModelManager compares against after copying from assets.
    val pocketTtsModelSha256 = "\"24dc3bd77dd48c291e52c297878d3437c9492f245d823d7f6a06c4bbb67f4b6b\""

    buildTypes {
        debug {
            buildConfigField("String", "POCKET_TTS_MODEL_SHA256", pocketTtsModelSha256)
        }
        release {
            buildConfigField("String", "POCKET_TTS_MODEL_SHA256", pocketTtsModelSha256)
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation(group = "", name = "sherpa-onnx-android", ext = "aar")

    // DataStore for persistent preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    testImplementation(libs.bundles.testing.jvm)
    testRuntimeOnly(libs.junit5.engine)
}
