#!/bin/bash
set -e

# sherpa-onnx Android AAR build script
SHERPA_ONNX_VERSION="v1.13.4"
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
BUILD_DIR="${SCRIPT_DIR}/build"
REPO_URL="https://github.com/k2-fsa/sherpa-onnx.git"

# Check prerequisites
if [ -z "$ANDROID_NDK" ]; then
    echo "Error: ANDROID_NDK environment variable not set"
    echo "Set it to your Android NDK installation path"
    exit 1
fi

if ! command -v cmake &> /dev/null; then
    echo "Error: cmake not found. Install CMake 3.21+"
    exit 1
fi

echo "Building sherpa-onnx ${SHERPA_ONNX_VERSION} for Android..."
echo "NDK: $ANDROID_NDK"

# Clone repo if needed
if [ ! -d "${BUILD_DIR}/sherpa-onnx" ]; then
    echo "Cloning sherpa-onnx..."
    mkdir -p "${BUILD_DIR}"
    cd "${BUILD_DIR}"
    git clone "${REPO_URL}"
    cd sherpa-onnx
    git checkout "${SHERPA_ONNX_VERSION}"
else
    echo "sherpa-onnx repo already exists at ${BUILD_DIR}/sherpa-onnx"
    cd "${BUILD_DIR}/sherpa-onnx"
fi

# Build AAR for arm64-v8a
echo "Building AAR..."
cd android/SherpaOnnxTtsEngine

# Use gradlew to build
if [ ! -f "gradlew" ]; then
    echo "Error: gradlew not found in android/SherpaOnnxTtsEngine"
    exit 1
fi

# Build release AAR
./gradlew --stacktrace assembleRelease -DANDROID_NDK="${ANDROID_NDK}"

# Find the built AAR
BUILT_AAR=$(find . -name "*release.aar" -type f | head -1)
if [ -z "$BUILT_AAR" ]; then
    echo "Error: Could not find built AAR"
    exit 1
fi

# Copy to output location
cp "${BUILT_AAR}" "${SCRIPT_DIR}/sherpa-onnx-android.aar"
echo "✓ Built AAR: ${SCRIPT_DIR}/sherpa-onnx-android.aar"
