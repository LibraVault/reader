# sherpa-onnx AAR Resolution Path

## Current Status

The sherpa-onnx reference app (`SherpaOnnxTtsEngine`) builds an APK, not a library AAR. The native C++ libraries must be compiled separately via the NDK/CMake build system.

## Solutions

### Option 1: Build Library Module from Source (Recommended)
The sherpa-onnx project likely has a library module that can be built as an AAR.

**Steps**:
```bash
cd third-party/sherpa-onnx/build/sherpa-onnx
find . -name "build.gradle*" -path "*lib*" | grep -v app | head -5
```

Look for a library module (e.g., `android/lib/build.gradle.kts` or similar).

If found:
```bash
cd android/SherpaOnnxLib  # or equivalent
./gradlew :lib:assembleRelease
# Output AAR will be at lib/build/outputs/aar/
```

### Option 2: Extract and Wrap Native Libraries
If sherpa-onnx builds native libraries via CMake:

```bash
cd third-party/sherpa-onnx/build/sherpa-onnx/android
# Find compiled .so files (likely in build/intermediates or similar)
find . -name "*.so" -path "*/arm64-v8a/*" 2>/dev/null

# Create proper AAR with these libraries:
# - AndroidManifest.xml
# - lib/arm64-v8a/*.so
# - lib/armeabi-v7a/*.so (if needed)
# - lib/x86/*.so (if needed)
```

### Option 3: Check Maven/Jitpack Artifacts
Pre-built AARs may now be available:

```bash
# Search Maven Central
curl -s "https://search.maven.org/solrsearch/select?q=sherpa-onnx+AND+android&rows=20" | jq '.response.docs'

# Search JCenter or other repos
# https://bintray.com/k2-fsa (if available)
```

### Option 4: Use Kotlin Native Bindings (Alternative)
If pure Kotlin/Native is an option (for desktop-only usage):
- Different build path
- May lose Android-specific optimizations
- Not recommended for this audio use case

## Implementation Impact

- **Steps 1-9 architecture**: ✅ **Complete and testable** (33 unit tests pass)
- **Runtime integration**: ⏳ **Blocked until AAR has native libraries**
- **Fallback**: App can run with Android system TTS only (Pocket TTS disabled)

## Next Action

1. **Investigate** sherpa-onnx library module (Option 1)
2. **If not found**: Extract native .so files and rebuild AAR properly
3. **Worst case**: Use pre-built GitHub releases or build from source with custom CMakeLists.txt

Once AAR is resolved:
- Replace `/third-party/sherpa-onnx/sherpa-onnx-android.aar`
- Run `./gradlew :core:tts:assemble` to verify integration
- Enable Pocket TTS in settings (already implemented)

## References

- sherpa-onnx GitHub: https://github.com/k2-fsa/sherpa-onnx
- Android NDK Guide: https://developer.android.com/ndk
- Gradle AAR Format: https://developer.android.com/studio/projects/android-library
