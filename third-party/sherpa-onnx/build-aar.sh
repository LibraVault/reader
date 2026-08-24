#!/bin/bash
set -e

# Builds third-party/sherpa-onnx/sherpa-onnx-android.aar from sherpa-onnx's
# own prebuilt GitHub Release binaries - NOT compiled from source.
#
# Earlier versions of this script tried to build via NDK/CMake against
# android/SherpaOnnxTtsEngine, which produces an APK (a demo app), not a
# library AAR - see AAR_RESOLUTION.md for why that path was abandoned.
# sherpa-onnx doesn't publish to Maven Central either. It does publish
# prebuilt per-ABI .so libraries directly on GitHub Releases, which this
# script downloads and repackages into a minimal AAR (just the native
# libraries under jni/<abi>/ - no compiled classes; the JNI Kotlin wrapper
# is vendored as source at
# core/tts/src/main/kotlin/xyz/libravault/core/tts/pocket/sherpa/Tts.kt).
#
# Only arm64-v8a is packaged, matching this project's single-ABI scope.

SHERPA_ONNX_VERSION="v1.13.4"
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
WORK_DIR="${SCRIPT_DIR}/build"
RELEASE_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/${SHERPA_ONNX_VERSION}/sherpa-onnx-${SHERPA_ONNX_VERSION}-android.tar.bz2"

if ! command -v zip &> /dev/null; then
    echo "Error: zip not found. Install zip." >&2
    exit 1
fi

rm -rf "${WORK_DIR}"
mkdir -p "${WORK_DIR}/jni/arm64-v8a"
cd "${WORK_DIR}"

echo "Downloading sherpa-onnx ${SHERPA_ONNX_VERSION} Android release..."
curl -sL -o release.tar.bz2 "${RELEASE_URL}"
tar xjf release.tar.bz2

cp jniLibs/arm64-v8a/*.so "jni/arm64-v8a/"

cat > AndroidManifest.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.k2fsa.sherpa.onnx">
</manifest>
EOF

# AAR spec expects a classes.jar even when there are no compiled classes to ship.
mkdir -p empty_jar_src
( cd empty_jar_src && jar cf ../classes.jar . )

zip -r -q sherpa-onnx-android.aar AndroidManifest.xml classes.jar jni
cp sherpa-onnx-android.aar "${SCRIPT_DIR}/sherpa-onnx-android.aar"

cd "${SCRIPT_DIR}"
sha256sum sherpa-onnx-android.aar > sherpa-onnx-android.aar.sha256

echo "✓ Built AAR: ${SCRIPT_DIR}/sherpa-onnx-android.aar"
echo "✓ Wrote provenance hash: ${SCRIPT_DIR}/sherpa-onnx-android.aar.sha256"
echo "  Verify with: ./gradlew :core:tts:assembleDebug"
echo "  Commit both the .aar and the updated .sha256 together."
