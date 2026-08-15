# sherpa-onnx Setup for Pocket TTS

## Overview

LibraVault uses [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) to power
the Pocket TTS on-device text-to-speech engine on **both platforms and both
Android flavors** — Play, F-Droid, and iOS all ship it, selectable in
Settings alongside the system voice.

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
   Both bundle it into the app at build time: Android via
   `third-party/sherpa-onnx/setup-android-model.sh`, which extracts it to
   `core/tts/src/main/assets/pocket-tts-model/` (committed to git, the same
   way `sherpa-onnx-android.aar` itself is); iOS via `setup-ios.sh` into
   `PocketTTSModel/` (gitignored, re-fetched per build/CI run — see "iOS
   engine binaries" below for why the two differ on that point). Neither
   platform downloads anything at app runtime, so both Android flavors and
   iOS get Pocket TTS with no INTERNET permission required for it.
   Previously Android downloaded the model on-device at first use instead
   (`PocketModelManager.kt`'s old design) — that needed the Play-only
   INTERNET permission and was why F-Droid didn't ship Pocket TTS at all.

## The voice model: what and why

The bundled voice is **`vits-piper-en_US-ljspeech-medium` (int8)**, a
single-speaker Piper VITS model. The URL/checksum live in two places that
must be kept in sync: `third-party/sherpa-onnx/setup-android-model.sh` and
`third-party/sherpa-onnx/setup-ios.sh` (each hardcodes its own copy, same as
they already do for engine binaries); `core/tts/build.gradle.kts`'s
`POCKET_TTS_MODEL_SHA256` build config field keeps only the checksum, as the
"which model version is this APK's bundled copy" marker `PocketModelManager`
checks after copying from assets into app storage.

This specific voice was a deliberate choice, not the first thing tried.
LibraVault has no paid tier today — the Ed25519-license-key/Play Billing
infrastructure this note originally referenced (`core/licensing`) was
removed; all features are free, donation-funded, and a subscription tier
is a possible future direction, not a near-term one. The model's license
still needed checking before shipping it regardless. Two more "obvious"
options were looked at and ruled out:

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
2. Update `MODEL_URL` / `MODEL_SHA256` in both
   `third-party/sherpa-onnx/setup-android-model.sh` and
   `third-party/sherpa-onnx/setup-ios.sh` (compute the SHA-256 of the
   `.tar.bz2` asset itself, e.g. `sha256sum vits-piper-<voice>.tar.bz2`), and
   `POCKET_TTS_MODEL_SHA256` in `core/tts/build.gradle.kts` to match.
3. Update the filenames in `PocketVoiceCatalog` (`MODEL_FILE_NAME`,
   `TOKENS_FILE_NAME`) to match the new voice's `.onnx`/`tokens.txt` names.
4. Re-run `./third-party/sherpa-onnx/setup-android-model.sh`, then
   `git add core/tts/src/main/assets/pocket-tts-model` and commit — the old
   model's stored `sha256.txt` (written into app storage, not the asset
   folder) stops matching the new `BuildConfig.POCKET_TTS_MODEL_SHA256`
   automatically, so existing installs re-copy once after updating.
5. Re-run `./third-party/sherpa-onnx/setup-ios.sh` (or let CI do it) to pick
   up the new model for the next iOS build.

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

## Why Android commits the model to git and iOS doesn't

Both platforms extract the same `.tar.bz2` at build/dev-setup time now, not
at app runtime — but where the extracted output lives differs:

- **Android**: `setup-android-model.sh` extracts straight into
  `core/tts/src/main/assets/pocket-tts-model/`, which gets **committed to
  git**, the same way `sherpa-onnx-android.aar` is (~11MB, already
  committed) — Gradle just picks it up as a normal asset, no CI step needed
  to fetch it, matching how Android's binary deps already work in this repo.
- **iOS**: `setup-ios.sh` extracts into a **gitignored** path and is re-run
  by CI on every build/TestFlight run (`ios-app-build.yml`,
  `ios-testflight.yml`), same as the two xcframeworks it also fetches. Those
  frameworks push the total per-build fetch to ~230MB — too large to commit
  — so the model rides along with the same fetch-at-build-time mechanism for
  consistency, even though on its own (~37MB) it wouldn't have forced that
  choice.

Either way, no network call happens when the app runs on a user's device —
only during development/CI, fetching a public, checksum-verified asset.

iOS's `PocketModelManager` just resolves a path inside the app bundle,
extraction having already happened at build time via `setup-ios.sh`. This
isn't a style preference relative to Android's old on-device-download
design (see git history) — it reflects a real platform gap: **Foundation
has no built-in tar/bzip2 decompression**, and sherpa-onnx's model releases
are `.tar.bz2`. The only on-device alternative — linking `libbz2.dylib`
directly, which IS present in the iOS SDK — needs `bzlib.h`, which Apple
doesn't ship publicly; that's a private-API App Store rejection risk.
Extracting at build time, on a machine with a real `tar`, sidesteps the
problem entirely — which is also why Android's own extraction moved to
build time instead of using `commons-compress` on-device once there was no
runtime download left to pair it with.
