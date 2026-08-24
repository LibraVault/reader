# Building third-party/sherpa-onnx/sherpa-onnx-android.aar

This directory holds the native engine binaries Pocket TTS runs on. They are
**downloaded prebuilt from sherpa-onnx's own GitHub Releases**, not compiled
from source locally — see `AAR_RESOLUTION.md` for why.

## Building

```bash
./build-aar.sh
```

Requires only `curl`, `tar`, `zip`, and `jar` (all standard). No Android
NDK/CMake/SDK needed for this step. Output:

```
third-party/sherpa-onnx/sherpa-onnx-android.aar
```

Downloads `sherpa-onnx-vX.Y.Z-android.tar.bz2` (~45 MB), extracts the
`arm64-v8a` `.so` libraries, and repackages them into a minimal AAR
(`jni/arm64-v8a/*.so` + an empty `classes.jar`). Takes a few seconds.

## Verifying

```bash
./gradlew :core:tts:assembleDebug
```

## Updating the sherpa-onnx version

Edit `SHERPA_ONNX_VERSION` in `build-aar.sh` and rebuild. Check
https://github.com/k2-fsa/sherpa-onnx/releases for available versions and
confirm the `android.tar.bz2` asset exists for that tag. Rebuilding also
regenerates `sherpa-onnx-android.aar.sha256` (see "Provenance" below) —
commit both files together so the hash always matches what's checked in.

## Provenance

`sherpa-onnx-android.aar` is a **binary blob committed to git** (~11 MB), which
is a classic supply-chain soft spot: nothing about the repo itself proves what
went into it. This is addressed two ways:

- **Upstream source and version** are pinned in `build-aar.sh`'s
  `SHERPA_ONNX_VERSION` (currently `v1.13.4`) and traceable to the exact
  upstream release asset it came from — see "Building" above.
- **Content integrity** is checked in as
  `sherpa-onnx-android.aar.sha256` (standard `sha256sum` checksum-file format).
  Verify the committed binary matches it at any time with:

  ```bash
  sha256sum -c sherpa-onnx-android.aar.sha256
  ```

  A mismatch means the `.aar` was modified without rebuilding via
  `build-aar.sh` and regenerating the hash — treat that as a signal to
  investigate before trusting the binary, not something to silently
  re-hash away.

This doesn't make the binary itself more trustworthy than sherpa-onnx's own
GitHub Release artifact is — it makes *tampering with the committed copy*
detectable, and keeps the upstream version this repo is actually running
traceable instead of implicit. See issue #531.

## The voice model is separate

This AAR only contains the engine (inference runtime). The voice model
itself is bundled separately, at build time, by `setup-android-model.sh`
into `core/tts/src/main/assets/pocket-tts-model/` (committed to git, like
this AAR) — see `SHERPA_ONNX_SETUP.md`.
