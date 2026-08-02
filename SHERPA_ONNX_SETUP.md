# sherpa-onnx Setup for Pocket TTS

## Overview

LibraVault uses [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) to power
the Pocket TTS on-device text-to-speech engine (Play flavor only — F-Droid
uses system TTS, see `TtsEngineFactory.isFdroidBuild()`).

Two independent pieces make this work, both sourced from sherpa-onnx's own
prebuilt GitHub Release assets rather than compiled/trained locally:

1. **The engine** — native `.so` libraries, packaged into
   `third-party/sherpa-onnx/sherpa-onnx-android.aar` by `build-aar.sh`. See
   `third-party/sherpa-onnx/BUILD.md`.
2. **The voice model** — downloaded on-device at first use by
   `PocketModelManager`, not committed to the repo (kept out of git to avoid
   bloating repo size; verified via SHA-256 on download).

## The voice model: what and why

The bundled voice is **`vits-piper-en_US-ljspeech-medium` (int8)**, a
single-speaker Piper VITS model, configured via
`core/tts/build.gradle.kts`'s `POCKET_TTS_MODEL_URL` /
`POCKET_TTS_MODEL_SHA256` build config fields.

This specific voice was a deliberate choice, not the first thing tried.
LibraVault is a commercial app (Play Billing Pro tier), so the model's
license needed checking before shipping it — two more "obvious" options were
looked at and ruled out:

- **sherpa-onnx's own "Pocket TTS" model family** (`sherpa-onnx-pocket-tts-*`)
  — same name as this feature, and `PocketVoiceCatalog`'s original design
  (WAV-based voice-cloning prompts, a bundled `bria.wav`) was clearly built
  around it. Its license is **CC-BY-NC 4.0 — non-commercial only**
  (confirmed from the model's own README, which points to
  https://github.com/kyutai-labs/pocket-tts). Can't ship in a paid app.
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

## Engine binaries

See `third-party/sherpa-onnx/BUILD.md` for building/updating
`sherpa-onnx-android.aar`. Short version: `./third-party/sherpa-onnx/build-aar.sh`,
then `./gradlew :core:tts:assembleDebug` to verify.
