# LibraVault Test Plan

> **Stale as of the Encrypted Vaults work (2026-08)**: the module list,
> file/test counts, and "v0.3.0-alpha" metrics below predate `core:vaultcrypto`/
> `core:vaultstore`/`core:vaultcontent`/`feature:vault` (and several other
> modules) entirely — treat the specific numbers as historical, not current.
> The `core/licensing` references have been removed (module deleted, no
> Pro/paid tier for now — see `docs/threat-model.md`'s "Out of scope"
> section), but this document otherwise still needs a full refresh against
> the current module set. Not done here — out of scope for the change that
> prompted this note.
>
> **2026-08-16 update:** the fabricated metrics table has now been deleted
> (see "Test Metrics & Health" below) and the CI table corrected. The full
> refresh of this document is tracked as Phase 6 of
> [`docs/TEST_COVERAGE_PRD.md`](TEST_COVERAGE_PRD.md), which also inventories
> the gaps this plan does not currently mention.

## Overview

This document describes the unit test strategy, current coverage, deliberate gaps, and future priorities for the LibraVault reader app. All unit tests use **JUnit 5 (Jupiter) + MockK + Turbine + kotlinx-coroutines-test** following a consistent house style across modules.

### Test Conventions

- **Naming**: Backtick sentence-style: `` fun `search debounces 300ms and calls searchLibrary`() ``
- **Organization**: Tests grouped with `// ── Section ──` comment dividers
- **Mocking**: MockK with `relaxed = true` for fire-and-forget deps; `coEvery`/`verify` for suspend calls
- **Flow Testing**: Turbine (`flow.test { awaitItem() }`) for StateFlow/Flow assertions
- **Dispatchers**: `Dispatchers.setMain(UnconfinedTestDispatcher())` in `@BeforeEach`, reset in `@AfterEach`
- **Assertions**: JUnit 5 static imports (`assertEquals`, `assertTrue`, etc.), not AssertJ

### Running Tests

```bash
# All unit tests
./gradlew test

# Specific module
./gradlew core:domain:testDebugUnitTest
./gradlew feature:library:testDebugUnitTest

# Variant-specific (some modules have play/fdroid flavors)
./gradlew feature:settings:testPlayDebugUnitTest
./gradlew feature:settings:testFdroidDebugUnitTest

# Instrumentation (on-device) tests — needs a connected device or emulator
./gradlew connectedDebugAndroidTest
```

**Pocket TTS audio output is arm64-only.** `PocketTtsAudioOutputTest` runs the
real sherpa-onnx pipeline, and the vendored AAR bundles `arm64-v8a` natives
only, so on an x86_64 emulator the test *skips* rather than fails. Running it
for real needs arm64 hardware:

```bash
# Locally, against a connected arm64 phone
./gradlew :core:tts:connectedDebugAndroidTest

# In CI, on a Firebase Test Lab physical device
gh workflow run android-tts-audio-test.yml
```

---

## Coverage Map by Module

> **This section used to be a hand-written per-module narrative with "✅ 80%
> COMPLETE" style verdicts. It has been replaced by measured data, because
> several of those verdicts were provably wrong by the time anyone read them:**
>
> - `core/database` was marked **"✅ 100% COMPLETE (logic-bearing migrations)"**.
>   In fact four of six migrations had no test at all, and the one that did
>   (`MIGRATION_4_5`) asserted SQL substrings against a mock rather than running
>   anything. Fixed in Phase 3 — `MigrationChainTest` now runs the full v1->v7
>   chain against real SQLite.
> - `feature/library` was marked **"🔴 DEFERRED — requires extensive DI/context
>   setup"**. Its display logic was in fact extractable to pure functions with no
>   DI at all, and now has 23 unit tests.
> - Percentages were estimates. The repo had no coverage instrumentation until
>   Phase 1, so none of them could be checked by a reader.
>
> Per-module numbers now come from Kover and are printed in every CI run's
> summary. See **Test Metrics & Health** below for the current table and the
> command to regenerate it. Prose here would go stale again within a week.

### What each module's coverage means

Line coverage is a floor, not a grade. Reading the table:

- **`core:*` modules are high and should stay high.** They are mostly pure logic
  with no Android dependencies, so anything uncovered there is uncovered by
  choice. The three vault modules are gated in CI.
- **`feature:*` modules are low largely because of Compose.** 58% of all
  uncovered lines in the repo are Compose UI (`*ScreenKt`, `*ComponentsKt`,
  `*SheetKt`), which sits at ~13% covered; non-UI logic is ~57% and ViewModels
  ~67%. A feature module at 26% is not 26% tested — its logic is far better
  covered than that, and its rendering is barely covered at all.
- **`app` is low and that is expected.** It is navigation wiring and
  `MainActivity`; the routing logic that carries risk is tested
  (`ScreenRouteTest`), the rest is glue.

## 🔴 INTENTIONAL GAPS (Deferred to instrumented or manual testing)

### Why These Are Out of Scope for Unit Tests

**Compose UI Screens** (feature/library/LibraryScreen, etc.)
- Requires Compose test framework or screenshot tests; not JVM-testable without Robolectric
- Covered by manual QA and Compose instrumentation tests (separate CI job recommended)

**Room DAO/Entity Declarations** (core/database)
- `@Database`, `@Entity`, `@Dao`, `@Query` are schema definitions with no testable logic
- Room's own test harness validates SQL correctness; we verify via instrumented tests
- Covered by integration tests with in-memory Room databases

**Android Keystore (real hardware-backed keys)** (core/vaultstore)
- `AndroidKeystoreHardwareKeyWrap` depends on OS-level Keystore APIs
  (StrongBox/TEE) — not mockable in a plain JVM test
- `VaultStore`'s own create/unlock/lock orchestration is unit-tested
  against `FakeHardwareKeyWrapFactory` instead (`VaultStoreTest.kt`); only
  `AndroidKeystoreHardwareKeyWrap` itself needs a real device, verified
  once via the Encrypted Vaults Phase 0 spike (both a budget and a
  flagship device confirmed hardware-backed keys)

**Media3 Framework Integration** (feature/player)
- MediaController callbacks, session/playback state serialization
- Requires Media3 test helpers or instrumentation
- Unit tests stub via mockk; instrumented tests verify real behavior

**TTS Engine Lifecycle** (core/tts)
- AndroidTtsEngine state machine tightly coupled to android.speech.tts.TextToSpeech
- Non-deterministic timing (API callbacks); not JVM-unit-testable
- Deferred to instrumented tests with device/emulator

**Pocket TTS audio output** (core/tts) — *closed by `PocketTtsAudioOutputTest`*
- sherpa-onnx synthesis and espeak-ng phonemization are native and arm64-only,
  so no JVM test can reach them; every other Pocket TTS test asserts on
  lifecycle and plumbing, never on the audio
- Covered on-device by `PocketTtsAudioOutputTest` (issue #107), which asserts
  real English text yields non-silent, plausibly-shaped, input-dependent PCM
- Assertions are aggregate and relative (RMS, silence fraction, duration vs.
  text length, duration vs. speed), not golden-waveform: ONNX output is not
  bit-identical across devices, and VITS samples from a noise distribution
- The measurement math itself is unit-tested on the JVM against synthetic
  waveforms (`PcmAnalysisTest`), so it cannot silently rot between arm64 runs

**PDF Rendering** (feature/reader, core/storage)
- PdfRenderer is device-only (requires Android 21+)
- Instrumented tests verify on actual device with test PDFs

**FileScanner SAF Wrappers** (core/storage)
- ContentResolver/SAF URI enumeration has no pure-function surface
- Deferred to integration tests with mock ContentProvider or instrumented tests

---

## Future Testing Priorities

Ordered by risk, not by how easy the tests are to write. Re-derive this from
the coverage table rather than trusting it — the previous version of this
section recommended writing tests for a **donation state machine that had
already been deleted** (`pollUntilPaid`, `createDonationInvoice`,
`hasAnySettledInvoice` all removed in PRs #163/#172, when the donation flow
became an external link). A stale priority list quietly sends people to work
that cannot be done.

### Tracked as issues

These have full plans attached and are the highest-value remaining work:

- **#253 — `AndroidKeystoreHardwareKeyWrap` has no coverage at any level.**
  Only the fake is exercised; the real Keystore path protects a vault against
  an offline attack on a 4-digit PIN. Needs an instrumented test — Robolectric's
  `AndroidKeyStore` is a shim. Note the CI emulator is software-backed, so
  `create()`'s happy path needs a physical device while everything else can run
  on the emulator via `forExistingKey()`.
- **#256 — iOS `PocketTTSEngine` is structurally untestable.** Every entry
  point returns early under XCTest, so any test that instantiates it exercises
  the guard and nothing else. Needs the seam extracted before it can be tested
  at all.
- **#273 — iOS snapshot baselines.** Deferred from Phase 5: without local macOS
  a baseline can be recorded but not visually approved, and an un-inspected
  baseline locks in whatever was wrong when it was recorded.

### Untracked, in rough priority order

1. **`feature:reader` (26.8%)** — `EpubReaderViewModel`'s state machine
   (`openPublication`, `onLocatorChanged`, chapter retrieval) and external-intent
   init. The largest feature module by line count and the most stateful; EPUB
   navigation plus progress persistence is exactly where races hide.
2. **`core:tts` (24.9%)** — the engine boundary. Android's Pocket TTS shipped
   producing no audio at all through v0.4.5-alpha (issue #107, fixed in #129)
   precisely because nothing tested this layer.
3. **`core:domain` (17.2%)** — a long tail of one-line delegating use cases at
   0%. Low risk individually, cheap to cover, and it is the module whose
   coverage claim was most wrong before measurement (the deleted table said 95%).
4. **`core:storage` (50.1%)** — `MetadataExtractor.extract()` for PDF and audio
   (page count, duration, cover extraction). Needs Robolectric for
   `BitmapFactory`; user-visible when it breaks.
5. **`feature:onboarding` (15.9%)** — small, and the first thing a new user
   touches.

### Deliberately not planned

- **Compose screen bodies.** ~58% of all uncovered lines in the repo are Compose
  UI. The way to cover these is to extract what a screen *decides* into
  `internal` functions (see `LibraryScreenLogic.kt`) and to screenshot what it
  *renders* — not to render screens and assert nothing. Rendering a screen to
  move a coverage number produces a test that cannot fail.
- **Hilt `@Module` classes.** Pure DI configuration, validated by the annotation
  processor at compile time.
- **Touch-target size assertions.** Compose expands `touchBoundsInRoot` to the
  minimum, so such a test cannot fail. Overlap and visual size are screenshot
  concerns. See `PlayerAccessibilityTest`'s KDoc.

## Test Metrics & Health

> **The table that used to live here has been deleted rather than updated.**
>
> It published per-module branch-coverage figures ("✅ 95%", "🟡 70%") and an
> overall "~85% logical branch coverage" claim. This repo has never had
> coverage instrumentation on either platform — no JaCoCo, no Kover, no
> `-enableCodeCoverage` — so those numbers were estimated by hand and could
> not be checked by anyone reading them. They were also stale: the table
> covered 9 modules and "~77 unit tests" against an actual 16 modules and
> **870** executed JVM tests as of `dev` @ `7f4712a` (2026-08-16).
>
> Unverifiable coverage numbers are worse than no numbers, because both humans
> and the dev agent read this file as ground truth.

**Measured coverage** (Kover, line coverage, debug variant) — regenerate with:

```bash
./gradlew koverXmlReportDebug \
          :app:koverXmlReportFdroidDebug \
          :feature:settings:koverXmlReportFdroidDebug
python3 scripts/coverage-summary.py
```

As of 2026-08-18 (`dev` @ `efb1b89`) — **overall 41.6%** (3,723 / 8,943 lines):

| Module | Coverage | | Module | Coverage |
|---|---:|---|---|---:|
| `core:logger` | 97.4% | | `feature:settings` | 47.2% |
| `core:vaultcrypto` 🔒 | 91.3% | | `feature:player` | 44.3% |
| `core:vaultstore` 🔒 | 83.4% | | `feature:vault` | 37.6% |
| `core:ui` | 77.9% | | `feature:reader` | 26.8% |
| `core:vaultcontent` 🔒 | 70.2% | | `feature:library` | 26.4% |
| `core:database` | 67.4% | | `core:tts` | 24.9% |
| `core:storage` | 50.1% | | `core:domain` | 17.2% |
| | | | `feature:onboarding` | 15.9% |
| | | | `app` | 15.4% |

Movement since the first measurement (2026-08-17, 40.1%) came from Phases 3–5:
`core:database` 63.3 -> 67.4 (full v1->v7 migration chain), `feature:player`
39.2 -> 44.3 (`PlaybackService`), `app` 8.9 -> 15.4 (nav routes + `FLAG_SECURE`),
`feature:library` 23.5 -> 26.4 (extracted display logic), `core:vaultcrypto`
90.3 -> 91.3 (HKDF known-answer vectors).

🔒 = gated. A drop of more than 1pp below `scripts/coverage-baseline.json`
fails CI. The other modules are report-only: a repo-wide ratchet mostly
produces tests written to move a number rather than to catch a bug.

Note that `core:domain` measures 17.2% where the deleted table claimed 95%.
That module's four tested use cases are each at 100%; what the number exposes
is a long tail of one-line delegating use cases and data-class boilerplate at
0%. Treat low numbers here as targeting information, not as alarm.

---

## Security & Correctness Validation

### Hardening Verified by Unit Tests

- ✅ **XXE Prevention** (core/storage): FEATURE_PROCESS_DOCDECL=false prevents DOCTYPE entity resolution in EPUB OPF parsing (MetadataExtractorOpfTest)
- ✅ **HTML Sanitization** (feature/reader): Jsoup script/style/iframe/SVG removal + text-only extraction for TTS (EpubStripHtmlTest)
- ✅ **Migration Idempotency** (core/database): MIGRATION_4_5 PRAGMA check prevents duplicate-column crash (MigrationsTest)
- ✅ **Encrypted Vault crypto** (core/vaultcrypto): chunked AES-256-GCM round-trip, tamper detection, deterministic per-chunk nonce derivation, a real Project Wycheproof known-answer vector — see `docs/threat-model.md`'s Encrypted Vaults rows for the full list
- ✅ **URL Validation** (feature/settings): Checkout-link HTTPS-only scheme allowlist (SafeCheckoutLinkTest)

### Regressions Protected

- ✅ **Donation Fallback Logic** (feature/settings): StaticAddressesTest rewritten to assert actual DonationState; previously a false-confidence test that would not catch fallback-routing bugs
- ✅ **Search Debounce** (feature/library): 300ms debounce interval enforced; prevents excessive database queries
- ✅ **Playback Speed Quantization** (core/domain): Quarter-step rounding prevents fractional UI glitches and API misalignment
- ✅ **Vault Recovery** (feature/library): Room-recovery race condition in init (timeout fallback) is now tested to ensure scan proceeds even if vault visibility waits >2 seconds

---

## CI/CD Integration

### Workflows that run tests

| Workflow | Trigger | What it runs |
|----------|---------|--------------|
| `jvm-tests.yml` | every push and PR | All JVM unit tests, plus `lint` |
| `ui-tests.yml` | nightly on `dev`; PRs to `main`; PRs to `dev` labelled `needs-emulator`; manual | `connectedDebugAndroidTest` on an x86_64 API 34 emulator |
| `android-tts-audio-test.yml` | manual dispatch | `PocketTtsAudioOutputTest` on a Firebase Test Lab **physical arm64** device |

The third exists because `ui-tests.yml`'s emulator is x86_64 and the
sherpa-onnx AAR is arm64-only, so the audio test can only skip there. Dispatch
it for release prep and for any change touching the voice model, the
`espeak-ng-data` bundle, the sherpa-onnx AAR, or `pocketTtsConfig`.

### Pre-Commit Hook (Suggested)

```bash
#!/bin/bash
# .git/hooks/pre-commit
./gradlew test --fail-fast || exit 1
```

---

## How to Add Tests

1. **Choose a pure function or ViewModel**: Avoid testing Compose, Room DAOs, or Media3 integration directly in unit tests.
2. **Follow naming conventions**: `` `behavior when condition`() ``; group related tests with `// ── Section ──`.
3. **Use house libraries**: MockK (`mockk<T>(relaxed = true)`), Turbine (`.test { awaitItem() }`), coroutines-test.
4. **Run locally**: `./gradlew <module>:testDebugUnitTest` before pushing.
5. **Document gaps**: If a function can't be unit-tested (Compose, Framework-bound), add a comment explaining why and which test type (instrumented/manual) covers it.

---

## Glossary

- **Unit Test**: Fast, isolated, in-process JVM test of pure functions or ViewModels with mocked dependencies.
- **Instrumented Test**: Slow, device-bound test using Android framework (emulator/real device required).
- **Integration Test**: Tests multiple modules together with a real database or ContentProvider.
- **Screenshot Test**: Visual regression tests for Compose screens; requires separate CI job.
- **Manual QA**: Human testing of UI flows, gestures, and edge cases not automatable.
