# Performance baseline (issue #695, Phase 0)

Tracks the state of this repo's macrobenchmark/startup-baseline infra. See
`docs/TEST_COVERAGE_PRD.md` §S3 for the gap this closes, and issue #695 for
the full multi-phase plan (this doc covers Phase 0 only).

## Status: infra in place, no numbers committed yet

Generating and running these benchmarks needs a physical Android device or
emulator, and a macOS/Xcode machine for the iOS side. Neither is available
in this repo's dev-agent CI runner, so the modules below exist and compile,
but have never actually executed. **Do not treat their absence as "no
regressions" — there is no baseline yet to regress against.**

Whoever runs these first (a maintainer, or a future device-enabled CI job —
see "Follow-up" below) should replace this section with the actual numbers
and the device/OS they were measured on.

## Android — `:benchmark` and `:baselineprofile`

Both modules benchmark the `fdroid` flavour only (see
`benchmark/build.gradle.kts` for why) and target `:app`'s release-shaped
build, so they don't need the real release signing keystore — they're
debug-signed.

```bash
# Startup benchmark (cold/warm/hot) — needs a connected device/emulator
./gradlew :benchmark:connectedFdroidBenchmarkAndroidTest

# Baseline Profile generation — writes
# app/src/release/generated/baselineProfiles/*.txt, which :app packages
# into the release APK automatically once generated
./gradlew :baselineprofile:generateBaselineProfile
```

Prefer running on a physical device over an emulator — macrobenchmark
timings from GitHub-hosted emulators are not representative (same caveat
`ui-tests.yml` already lives with for instrumented tests).

## iOS — `LibraVaultUITestsLaunchTests.testLaunchPerformance`

```
xcodebuild test -scheme LibraVault \
  -only-testing:LibraVaultUITests/LibraVaultUITestsLaunchTests/testLaunchPerformance
```

or run it directly from Xcode (Product > Test). Needs a Mac; this repo's
dev-agent CI runs on Linux and cannot build or run this at all.

## Follow-up (out of scope for this PR)

- Per-screen frame-timing benchmarks (Phase 0b) for `LibraryScreen`, Reader,
  Player.
- CI wiring — a `needs-perf-check` label gate + Firebase Test Lab physical
  devices for Android, Simulator for iOS (Phase 1).
- `docs/PERF_DEBUGGING.md` manual debugging toolkit (Phase 2).
