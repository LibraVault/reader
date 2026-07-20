# sherpa-onnx Setup for Pocket TTS

## Overview

LibraVault uses sherpa-onnx to power the Pocket TTS on-device text-to-speech engine. The AAR is built locally from source using the Android NDK.

## Initial Setup (One-time)

### 1. Install prerequisites

```bash
# Download Android NDK (r26 recommended)
# https://developer.android.com/ndk/downloads

# Install CMake (if not already via Android Studio)
brew install cmake  # macOS
apt-get install cmake  # Linux

# Verify installations
$ANDROID_NDK/bin/ndk-build --version
cmake --version
```

### 2. Set environment variables

```bash
export ANDROID_NDK=/path/to/android-ndk-r26
export CMAKE=/usr/bin/cmake  # or your cmake path
```

Or add to `local.properties` in the project root (create if it doesn't exist):
```properties
ndk.dir=/path/to/android-ndk-r26
cmake.dir=/usr/bin/cmake
```

### 3. Build the AAR

```bash
cd third-party/sherpa-onnx
./build-aar.sh
```

This clones sherpa-onnx, builds the Android AAR for arm64-v8a, and outputs:
```
third-party/sherpa-onnx/sherpa-onnx-android.aar
```

Build time: ~10-15 minutes on a modern machine.

### 4. Verify the build

```bash
./gradlew :core:tts:assemble
```

Should complete without dependency errors.

## Development

Once the AAR is built, regular builds reference it via the `flatDir` repository in `core/tts/build.gradle.kts`. No further action needed.

### Updating sherpa-onnx version

Edit `SHERPA_ONNX_VERSION` in `third-party/sherpa-onnx/build-aar.sh` and rebuild:
```bash
./build-aar.sh
```

## CI/CD Integration

In your CI pipeline, add:
```bash
./third-party/sherpa-onnx/build-aar.sh
```

Optionally cache the built AAR to speed up subsequent builds.

## Troubleshooting

**"ANDROID_NDK environment variable not set"**
- See step 2 above. Set `ANDROID_NDK` or update `local.properties`.

**"cmake not found"**
- Install CMake (step 1) and ensure it's in `$PATH`.

**Build fails during CMake configure**
- Check that NDK version is r26 or compatible. Older/newer versions may have issues.
- Ensure sufficient disk space (build needs ~2GB).

**AAR ends up empty or corrupted**
- Delete `third-party/sherpa-onnx/build/` and rebuild.

## Notes

- The AAR is self-contained (includes all native libraries for arm64-v8a)
- Multi-architecture support (armv7, x86, x86_64) is possible but not configured by default
- The model files (tarball URL) are configured in `core/tts/build.gradle.kts` as `POCKET_TTS_MODEL_URL`
