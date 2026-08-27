# :benchmark

Jetpack Macrobenchmark module. Tracks [#695](https://github.com/LibraVault/reader/issues/695) —
before this module existed, the repo had no UI performance baseline at all,
which is a large part of why regression hunts like #653 took as long as they did.

## What's here (Phase 0)

`StartupBenchmark` — cold/warm/hot app-launch timing, run against `:app`'s
`benchmark` build type (release-shaped: minified, non-debuggable, no Baseline
Profile yet — see the class doc comment for why).

## Running it

Needs a physical device or a Gradle Managed Device — the Macrobenchmark
library actively refuses to run on most emulators (unrepresentative timing).

```
./gradlew :benchmark:connectedFdroidBenchmarkAndroidTest   # attached physical device
./gradlew :benchmark:pixel6Api34BenchmarkAndroidTest        # local Gradle Managed Device
```

Results land in `benchmark/build/outputs/androidTest-results/` — both a
human-readable summary and a Perfetto trace per iteration (open the trace in
https://ui.perfetto.dev for a full timeline).

## What's not here yet

Tracked as follow-ups on #695, not silently dropped:

- **Phase 0b** — per-screen frame-timing / scroll-jank benchmarks for
  Library, Reader, and Player (the surfaces `docs/TEST_COVERAGE_PRD.md` §S2
  flags as highest-traffic and least tested). Needs Compose test tags wired
  into those screens first.
- **Baseline Profile generation** — would turn the `CompilationMode.None()`
  numbers above into a faster real-world baseline; deliberately not bundled
  into this PR to keep it reviewable.
- **Phase 1** — CI regression gate (`needs-perf-check` label, mirroring
  `ui-tests.yml`'s `needs-emulator` pattern) running this on Firebase Test Lab
  physical hardware, with a fail threshold vs. a committed baseline.
