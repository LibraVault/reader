# sherpa-onnx AAR Resolution — RESOLVED

## Final approach

`sherpa-onnx-android.aar` is built by `build-aar.sh` from sherpa-onnx's own
prebuilt GitHub Release binaries (`sherpa-onnx-vX.Y.Z-android.tar.bz2`), not
compiled from source. It packages only the `arm64-v8a` `.so` libraries under
`jni/arm64-v8a/` — no compiled classes. The Kotlin JNI wrapper that calls
into those libraries is vendored as source at
`core/tts/src/main/kotlin/com/k2fsa/sherpa/onnx/Tts.kt`
(copied from sherpa-onnx's own `android/SherpaOnnxTts` sample, Apache-2.0),
so it's compiled by `core:tts` itself rather than shipped inside the AAR.

**That path is load-bearing — keep the file in `com.k2fsa.sherpa.onnx`.** The
wrapper's `external fun`s are statically-registered JNI methods, resolved by a
symbol name derived from the fully-qualified class name (the `.so` exports
`Java_com_k2fsa_sherpa_onnx_OfflineTts_newFromFile` and friends). The file was
originally repackaged into `xyz.libravault.core.tts.pocket.sherpa`, which
compiled and linked fine and then threw `UnsatisfiedLinkError` on the first
native call — Pocket TTS produced no audio at all on Android until this was
found by the on-device test added for issue #107. Release builds additionally
need the keep rules in `app/proguard-rules.pro`, since R8 renaming these
classes breaks resolution the same way.

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
