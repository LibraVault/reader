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

    buildTypes {
        debug {
            buildConfigField("String", "POCKET_TTS_MODEL_URL", "\"https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.10.0/sherpa-onnx-v1.10.0-android-arm64-v8a.tar.gz\"")
        }
        release {
            buildConfigField("String", "POCKET_TTS_MODEL_URL", "\"https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.10.0/sherpa-onnx-v1.10.0-android-arm64-v8a.tar.gz\"")
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation(group = "", name = "sherpa-onnx-android", ext = "aar")

    // DataStore for persistent preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // HTTP client for model downloads
    implementation("com.squareup.okhttp3:okhttp:4.11.0")

    // Tar/gzip extraction for model archives
    implementation("org.apache.commons:commons-compress:1.24.0")

    testImplementation(libs.bundles.testing.jvm)
    testRuntimeOnly(libs.junit5.engine)
}
