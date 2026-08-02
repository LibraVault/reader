# Building third-party/sherpa-onnx/sherpa-onnx-android.aar

This directory holds the native engine binaries Pocket TTS runs on. They are
**downloaded prebuilt from sherpa-onnx's own GitHub Releases**, not compiled
from source locally — see `AAR_RESOLUTION.md` for why.

## Building

```bash
./build-aar.sh
```

Requires only `curl`, `tar`, `zip`, and `jar` (all standard). No Android
NDK/CMake/SDK needed for this step. Output:

```
third-party/sherpa-onnx/sherpa-onnx-android.aar
```

Downloads `sherpa-onnx-vX.Y.Z-android.tar.bz2` (~45 MB), extracts the
`arm64-v8a` `.so` libraries, and repackages them into a minimal AAR
(`jni/arm64-v8a/*.so` + an empty `classes.jar`). Takes a few seconds.

## Verifying

```bash
./gradlew :core:tts:assembleDebug
```

## Updating the sherpa-onnx version

Edit `SHERPA_ONNX_VERSION` in `build-aar.sh` and rebuild. Check
https://github.com/k2-fsa/sherpa-onnx/releases for available versions and
confirm the `android.tar.bz2` asset exists for that tag.

## The voice model is separate

This AAR only contains the engine (inference runtime). The voice model
itself is downloaded on-device at runtime by `PocketModelManager`, from the
URL/checksum in `core/tts/build.gradle.kts` — see `SHERPA_ONNX_SETUP.md`.
