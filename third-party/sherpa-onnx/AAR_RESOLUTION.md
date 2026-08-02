# sherpa-onnx AAR Resolution — RESOLVED

## Final approach

`sherpa-onnx-android.aar` is built by `build-aar.sh` from sherpa-onnx's own
prebuilt GitHub Release binaries (`sherpa-onnx-vX.Y.Z-android.tar.bz2`), not
compiled from source. It packages only the `arm64-v8a` `.so` libraries under
`jni/arm64-v8a/` — no compiled classes. The Kotlin JNI wrapper that calls
into those libraries is vendored as source at
`core/tts/src/main/kotlin/xyz/libravault/core/tts/pocket/sherpa/Tts.kt`
(copied from sherpa-onnx's own `android/SherpaOnnxTts` sample, Apache-2.0),
so it's compiled by `core:tts` itself rather than shipped inside the AAR.

## What was tried and ruled out

- **Option 1 (library module via Gradle)**: `android/SherpaOnnxTtsEngine`
  builds an APK (a demo app), not a library AAR — there's no Gradle library
  module producing one. Ruled out.
- **NDK/CMake source build**: what the original `build-aar.sh` attempted.
  Works in principle but is slow (~10-15 min), needs NDK r26 + CMake
  installed locally, and was never actually gotten past step 1 above (it
  called into the same non-library `SherpaOnnxTtsEngine` module). Ruled out
  in favor of the much simpler prebuilt-binary approach once it became clear
  sherpa-onnx ships those binaries directly on GitHub Releases.
- **Maven Central / JitPack**: sherpa-onnx does not publish an Android AAR to
  either. Ruled out.

## Why the model download URL matters too

The model (voice) is a separate concern from the engine binaries above.
`core/tts/build.gradle.kts`'s `POCKET_TTS_MODEL_URL` previously pointed at
the *engine* release tarball by mistake — see `SHERPA_ONNX_SETUP.md` for the
real voice model and the licensing reasoning behind which one was chosen.
