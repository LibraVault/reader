#!/bin/bash
set -e

# Fetches the build-time dependencies Pocket TTS needs on iOS: two static
# xcframeworks (sherpa-onnx itself, and its onnxruntime dependency - sherpa's
# release binary does NOT statically include onnxruntime, it must be linked
# separately) plus the bundled voice model.
#
# None of these are committed to git - together they're ~230MB, versus
# ~11MB for the equivalent Android AAR, so this project fetches them fresh
# at build/dev-setup time instead (see SHERPA_ONNX_SETUP.md). Run this once
# before opening the Xcode project, and it's also run as a CI step in
# .github/workflows/ios-app-build.yml.
#
# The voice model is bundled into the app (not downloaded on first use like
# Android's PocketModelManager does) because iOS's Foundation has no built-in
# tar/bzip2 decompression, and the only alternative - linking libbz2.dylib
# directly - uses headers Apple doesn't ship publicly, which is an App Store
# rejection risk for using a private API. Extracting the model here, at
# build time (where a real `tar` exists), sidesteps that entirely.

SHERPA_ONNX_VERSION="v1.13.4"
ONNXRUNTIME_VERSION="1.27.0"
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
REPO_ROOT="$( cd "${SCRIPT_DIR}/../.." && pwd )"
WORK_DIR="${SCRIPT_DIR}/build-ios"
FRAMEWORKS_DIR="${SCRIPT_DIR}/ios"
MODEL_DEST="${REPO_ROOT}/ios/LibraVaultApp/LibraVault/LibraVault/PocketTTSModel"

# Same voice as Android (core/tts/build.gradle.kts) - do not change without
# re-reading SHERPA_ONNX_SETUP.md's licensing section first.
MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-ljspeech-high-int8.tar.bz2"
MODEL_SHA256="916b2526d4ea191f9710bd2753698ac97926ec38eade867408d3f5fd422ca285"

verify_sha256() {
  local file="$1" expected="$2"
  local actual
  if command -v sha256sum &> /dev/null; then
    actual=$(sha256sum "$file" | awk '{print $1}')
  else
    actual=$(shasum -a 256 "$file" | awk '{print $1}')
  fi
  if [ "$actual" != "$expected" ]; then
    echo "Error: checksum mismatch for $file" >&2
    echo "  expected: $expected" >&2
    echo "  actual:   $actual" >&2
    exit 1
  fi
}

mkdir -p "${WORK_DIR}" "${FRAMEWORKS_DIR}"
cd "${WORK_DIR}"

if [ ! -d "${FRAMEWORKS_DIR}/sherpa-onnx.xcframework" ]; then
  echo "Downloading sherpa-onnx ${SHERPA_ONNX_VERSION} iOS xcframework..."
  curl -sL -o sherpa-ios.zip "https://github.com/k2-fsa/sherpa-onnx/releases/download/${SHERPA_ONNX_VERSION}/sherpa-onnx-${SHERPA_ONNX_VERSION}-ios.xcframework.zip"
  verify_sha256 sherpa-ios.zip "c5a62904bba73edc4bac89bbf51b4c3db1dd6c1b397a16ee95b2ff94701e9846"
  unzip -q -o sherpa-ios.zip
  rm -rf "${FRAMEWORKS_DIR}/sherpa-onnx.xcframework"
  mv sherpa-onnx.xcframework "${FRAMEWORKS_DIR}/"
  echo "✓ sherpa-onnx.xcframework"
else
  echo "sherpa-onnx.xcframework already present, skipping"
fi

if [ ! -d "${FRAMEWORKS_DIR}/onnxruntime.xcframework" ]; then
  echo "Downloading onnxruntime ${ONNXRUNTIME_VERSION} static iOS xcframework..."
  curl -sL -o onnxruntime-ios.zip "https://github.com/csukuangfj/onnxruntime-libs/releases/download/v${ONNXRUNTIME_VERSION}/onnxruntime-ios-static-xcframework-${ONNXRUNTIME_VERSION}.zip"
  verify_sha256 onnxruntime-ios.zip "2c4b6eda7fcf03ca51814bbc88e3709cc080e623581fce085286182cc30d60c1"
  unzip -q -o onnxruntime-ios.zip
  rm -rf "${FRAMEWORKS_DIR}/onnxruntime.xcframework"
  mv "onnxruntime-ios-static-xcframework-${ONNXRUNTIME_VERSION}/onnxruntime.xcframework" "${FRAMEWORKS_DIR}/"
  echo "✓ onnxruntime.xcframework"
else
  echo "onnxruntime.xcframework already present, skipping"
fi

if [ ! -d "${MODEL_DEST}" ] || [ -z "$(ls -A "${MODEL_DEST}" 2>/dev/null)" ]; then
  echo "Downloading bundled voice model (vits-piper-en_US-ljspeech-high, int8)..."
  curl -sL -o model.tar.bz2 "${MODEL_URL}"
  verify_sha256 model.tar.bz2 "${MODEL_SHA256}"
  rm -rf model-extract && mkdir model-extract
  tar xjf model.tar.bz2 -C model-extract
  rm -rf "${MODEL_DEST}"
  mkdir -p "${MODEL_DEST}"
  # The archive wraps everything in a single top-level directory - flatten it
  # so PocketTTSEngine.swift can reference files directly under PocketTTSModel/.
  mv model-extract/*/* "${MODEL_DEST}/"
  echo "✓ voice model bundled at ${MODEL_DEST}"
else
  echo "Voice model already present, skipping"
fi

echo "Done. Verify with: xcodebuild build -project ios/LibraVaultApp/LibraVault/LibraVault.xcodeproj -scheme LibraVault -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO"
