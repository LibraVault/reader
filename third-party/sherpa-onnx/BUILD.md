# Building sherpa-onnx for Android

This directory contains the build infrastructure for compiling sherpa-onnx's Android AAR locally.

## Prerequisites

- Android NDK (tested with r26)
- CMake (3.21+)
- Python 3 (for sherpa-onnx build scripts)
- Git

Set environment variables:
```bash
export ANDROID_NDK=/path/to/android-ndk
export CMAKE=/path/to/cmake
```

Or configure via `local.properties` in the root of the repo.

## Building

Run the build script:
```bash
./build-aar.sh
```

This will:
1. Clone sherpa-onnx (if not already present)
2. Build the Android AAR for arm64-v8a
3. Output to `sherpa-onnx-android.aar`

## Output

The built AAR will be at:
```
third-party/sherpa-onnx/sherpa-onnx-android.aar
```

This is automatically referenced by `core:tts` in its build.gradle.kts.

## Updating sherpa-onnx version

Edit `SHERPA_ONNX_VERSION` in `build-aar.sh` and rebuild.

## Notes

- Build time: ~10-15 minutes on a modern machine
- Output size: ~50 MB (uncompressed)
- The AAR is self-contained and includes all necessary native libraries
