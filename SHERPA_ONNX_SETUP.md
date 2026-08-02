# sherpa-onnx Setup for Pocket TTS

## Overview

LibraVault uses [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) to power
the Pocket TTS on-device text-to-speech engine on **both platforms**:
Android (Play flavor only — F-Droid uses system TTS, see
`TtsEngineFactory.isFdroidBuild()`) and iOS (selectable in Settings > Voice,
alongside the system voice).

Three independent pieces make this work, all sourced from sherpa-onnx's own
prebuilt GitHub Release assets rather than compiled/trained locally:

1. **The Android engine** — native `.so` libraries, packaged into
   `third-party/sherpa-onnx/sherpa-onnx-android.aar` by `build-aar.sh`. See
   `third-party/sherpa-onnx/BUILD.md`.
2. **The iOS engine** — two static xcframeworks (sherpa-onnx itself, and its
   onnxruntime dependency — sherpa's release binary does NOT statically
   include onnxruntime on iOS, unlike bundling it might suggest), fetched by
   `third-party/sherpa-onnx/setup-ios.sh`. See "iOS engine binaries" below.
3. **The voice model** — same model, same URL/checksum, on both platforms.
   Android downloads it on-device at first use (`PocketModelManager.kt`);
   iOS bundles it into the app at build time instead (`PocketModelManager.swift`
   — see "iOS: why the model is bundled, not downloaded" below). Never
   committed to git either way (kept out of the repo to avoid bloating repo
   size; verified via SHA-256 on fetch).

## The voice model: what and why

The bundled voice is **`vits-piper-en_US-ljspeech-medium` (int8)**, a
single-speaker Piper VITS model, configured via
`core/tts/build.gradle.kts`'s `POCKET_TTS_MODEL_URL` /
`POCKET_TTS_MODEL_SHA256` build config fields.

This specific voice was a deliberate choice, not the first thing tried.
LibraVault doesn't have a live paid tier today (per `KNOWN_LIMITATIONS.md`:
"no Pro tier beyond the simple unlock"), but it does ship Play Billing /
license-verification infrastructure (`core/licensing`) toward one that isn't
launched yet — so the model's license needed checking before shipping it
regardless, to avoid a licensing cleanup once that goes live. Two more
"obvious" options were looked at and ruled out:

- **sherpa-onnx's own "Pocket TTS" model family** (`sherpa-onnx-pocket-tts-*`)
  — same name as this feature, and `PocketVoiceCatalog`'s original design
  (WAV-based voice-cloning prompts, a bundled `bria.wav`) was clearly built
  around it. Its license is **CC-BY-NC 4.0 — non-commercial only**
  (confirmed from the model's own README, which points to
  https://github.com/kyutai-labs/pocket-tts). Too risky to bundle given
  where this app is headed.
- **Piper's `lessac` and `amy` voices** (the usual defaults recommended
  elsewhere) — both are derived from a Blizzard-2013-licensed voice whose
  license explicitly forbids commercial use of any derivative, including TTS
  products.
- **`ljspeech`** — trained from scratch (not finetuned from a restricted
  voice) on the [LJSpeech dataset](https://keithito.com/LJ-Speech-Dataset/),
  which is public domain. Safe to ship. This is what's configured.

Consequence: `PocketVoiceCatalog` was reworked from its original WAV-scanning
design to reflect this single bundled VITS voice instead — see the class's
own doc comment. v1 ships exactly one voice; multi-voice support would need
each additional voice's license checked the same way before adding it.

## Updating the voice model

1. Pick a voice from https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
   and **check its `MODEL_CARD` file's dataset license** before using it —
   this is the step that was missed for `lessac`/`amy` above.
2. Update `POCKET_TTS_MODEL_URL` / `POCKET_TTS_MODEL_SHA256` in
   `core/tts/build.gradle.kts` (compute the SHA-256 of the `.tar.bz2` asset
   itself, e.g. `sha256sum vits-piper-<voice>.tar.bz2`).
3. Update the filenames in `PocketVoiceCatalog` (`MODEL_FILE_NAME`,
   `TOKENS_FILE_NAME`) to match the new voice's `.onnx`/`tokens.txt` names.
4. Bump `PocketModelManager`'s stored `sha256.txt` invalidates automatically
   since it's compared against the new `BuildConfig.POCKET_TTS_MODEL_SHA256`
   at runtime — existing installs will re-download once.

## Android engine binaries

See `third-party/sherpa-onnx/BUILD.md` for building/updating
`sherpa-onnx-android.aar`. Short version: `./third-party/sherpa-onnx/build-aar.sh`,
then `./gradlew :core:tts:assembleDebug` to verify.

## iOS engine binaries

```bash
./third-party/sherpa-onnx/setup-ios.sh
```

Fetches (into gitignored local paths, not committed — see that script's own
header comment for why, ~230MB combined):

- `third-party/sherpa-onnx/ios/sherpa-onnx.xcframework` — sherpa-onnx itself
- `third-party/sherpa-onnx/ios/onnxruntime.xcframework` — its onnxruntime
  dependency, from `csukuangfj/onnxruntime-libs` (a sherpa-onnx maintainer's
  own mirror — this is what sherpa-onnx's official `build-ios.sh` fetches
  too, not a third-party substitute)
- `ios/LibraVaultApp/LibraVault/LibraVault/PocketTTSModel/` — the bundled
  voice model (see below)

Both xcframeworks are **static** libraries (`.a`, not dynamic `.framework`s),
linked via the `LibraVault` target's "Frameworks, Libraries, and Embedded
Content" (no embed/copy step needed for static libs) plus a bridging header
(`SherpaOnnx-Bridging-Header.h`) exposing sherpa-onnx's C API to Swift —
`Sources/KmpInterop/PocketTTS/SherpaOnnx.swift` (vendored from sherpa-onnx's
own `swift-api-examples/SherpaOnnx.swift`, Apache-2.0) calls those C
functions directly, predating sherpa-onnx's newer official SPM support.

Run this once before opening the Xcode project locally; `ios-app-build.yml`
runs it as a CI step (cached across runs, keyed on the script's own hash).

## iOS: why the model is bundled, not downloaded

Android's `PocketModelManager` downloads and extracts the model on first use
(`.tar.bz2`, verified via SHA-256). iOS's `PocketModelManager` just resolves
a path inside the app bundle — the model is fetched and extracted by
`setup-ios.sh` at build time instead. This isn't a style preference, it's a
real platform gap: **Foundation has no built-in tar/bzip2 decompression**,
and sherpa-onnx's model releases are `.tar.bz2`. The only on-device
alternative — linking `libbz2.dylib` directly, which IS present in the iOS
SDK — needs `bzlib.h`, which Apple doesn't ship publicly; that's a private-API
App Store rejection risk. Extracting at build time, on a machine with a real
`tar`, sidesteps the problem entirely.

Practical difference from Android: the ~37MB model adds to the iOS app's
install size instead of a Settings-triggered download. `TtsSettingsSection`'s
Android-side download-progress UI has no iOS equivalent for the same reason
— there's nothing to show progress for.
