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

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        // PCM measurement helpers shared by the JVM unit tests (which check the
        // math against synthetic waveforms, on every push) and the on-device
        // audio test (which applies it to real sherpa-onnx output, only on
        // arm64 hardware). Compiled into both test source sets rather than into
        // main, since production code has no use for them.
        getByName("test").kotlin.srcDir("src/testShared/kotlin")
        getByName("androidTest").kotlin.srcDir("src/testShared/kotlin")
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
    val pocketTtsModelSha256 = "\"916b2526d4ea191f9710bd2753698ac97926ec38eade867408d3f5fd422ca285\""

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

    // ── Instrumentation tests ──
    // PocketTtsAudioOutputTest runs the real sherpa-onnx pipeline on device.
    // The AAR is arm64-v8a only, so it self-skips elsewhere (see that file).
    androidTestImplementation(libs.bundles.testing.instrumentation)
}
