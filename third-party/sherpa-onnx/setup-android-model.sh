#!/bin/bash
set -e

# Fetches the Pocket TTS voice model and bundles it into the Android app at
# build time, mirroring build-aar.sh's approach for the engine binaries:
# download sherpa-onnx's own prebuilt release asset, verify it, and commit
# the result — no fetch happens at app runtime or in CI.
#
# Earlier versions of PocketModelManager.kt downloaded and extracted this
# same archive on-device on first use. That worked, but meant the Play
# flavor made a real (if user-data-free) network call on first Pocket TTS
# use, and it meant the F-Droid flavor couldn't offer Pocket TTS at all
# (F-Droid ships with no INTERNET permission - see app/src/fdroid's
# manifest). Extracting here, once, at dev/release-prep time, and committing
# the plain files under core/tts/src/main/assets/ removes the runtime
# network dependency entirely, the same way setup-ios.sh's build-time
# extraction does for iOS - see SHERPA_ONNX_SETUP.md.
#
# Same voice/URL/checksum as iOS (setup-ios.sh) and as documented in
# core/tts/build.gradle.kts - do not change without re-reading
# SHERPA_ONNX_SETUP.md's licensing section first.

MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-ljspeech-high-int8.tar.bz2"
MODEL_SHA256="916b2526d4ea191f9710bd2753698ac97926ec38eade867408d3f5fd422ca285"

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
REPO_ROOT="$( cd "${SCRIPT_DIR}/../.." && pwd )"
WORK_DIR="${SCRIPT_DIR}/build-android-model"
ASSET_DEST="${REPO_ROOT}/core/tts/src/main/assets/pocket-tts-model"

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

rm -rf "${WORK_DIR}"
mkdir -p "${WORK_DIR}"
cd "${WORK_DIR}"

echo "Downloading Pocket TTS voice model..."
curl -sL -o model.tar.bz2 "${MODEL_URL}"
verify_sha256 model.tar.bz2 "${MODEL_SHA256}"

echo "Extracting..."
tar xjf model.tar.bz2

# sherpa-onnx model releases wrap everything in a single top-level directory
# (e.g. vits-piper-en_US-ljspeech-medium-int8/model.onnx). Strip it so the
# asset files land flat under ASSET_DEST, matching what PocketVoiceCatalog
# expects (MODEL_FILE_NAME/TOKENS_FILE_NAME/DATA_DIR_NAME resolved directly
# under the model directory) and what setup-ios.sh already does for iOS.
EXTRACTED_DIR=$(find . -mindepth 1 -maxdepth 1 -type d | head -1)
if [ -z "${EXTRACTED_DIR}" ]; then
  echo "Error: expected a single top-level directory in the archive, found none." >&2
  exit 1
fi

rm -rf "${ASSET_DEST}"
mkdir -p "${ASSET_DEST}"
cp -R "${EXTRACTED_DIR}/." "${ASSET_DEST}/"

cd "${REPO_ROOT}"
rm -rf "${WORK_DIR}"

echo "✓ Bundled voice model at ${ASSET_DEST}"
echo "  git add core/tts/src/main/assets/pocket-tts-model"
echo "  Verify with: ./gradlew :core:tts:assembleDebug"
