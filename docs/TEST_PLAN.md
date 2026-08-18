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

### 🟢 WELL-COVERED (Unit tests exist, critical paths tested)

#### core/domain
- **UserPreferencesTest** ✅: `snapPlaybackSpeed()` (quarter-step quantization, boundary clamps), `formatPlaybackSpeed()` (integer vs fractional rendering, locale-independent dot separator)
- **MediaFormatTest** ✅: parametrized `isAudio()` across all enum values
- **AddVaultFolderUseCaseTest** ✅: existing-URI deduplication, new-URI insertion
- **ScanVaultUseCaseTest** (pre-existing): Observable scan flow with error handling
- **Status**: All pure functions testable without Android framework. ✅ COMPLETE.

#### core/storage
- **CoverArtCacheTest** (pre-existing): `calculateSampleSize()` logic (power-of-two invariant, 16x cap, 0/negative inputs)
- **MediaFormatTest**: Format-string parsing round-trips
- **LibraryScannerImplTest** (pre-existing): Scanner state machine and progress tracking
- **MetadataExtractorOpfTest** ✅: EPUB OPF parsing (title/author extraction, calibre series metadata, cover resolution by manifest `id`→`href` lookup, fallbacks to "Unknown"), **XXE prevention via DOCTYPE disabling** (validates hardening from commit 90680a1)
- **Status**: OPF parsing and XXE hardening now tested; audio/PDF extraction, `CoverArtCache` save/evict methods remain untested (require Robolectric for BitmapFactory). ✅ 80% COMPLETE.

#### feature/player
- **PlayerViewModelTest** (pre-existing): Item loading, error state, sheet visibility, chapter navigation bounds, sleep timer, retry logic
- **SleepTimerTest** (pre-existing): Timer state transitions
- **SeekClampTest** (pre-existing): Boundary clamping (near 0/duration, C.TIME_UNSET)
- **SkipDurationPreferenceTest** (pre-existing): Duration extraction from prefs
- **LibravaultMediaCallbackStripTest** (pre-existing): Static Media3 CommandButton building helpers
- **Status**: ViewModel core logic covered; playback transport (`play()`, `togglePlayPause()`, `seekTo()`, `setSpeed()`), bookmark CRUD, and retry backoff remain untested. ✅ 70% COMPLETE (intentional: remaining requires Media3 stubs).

#### feature/reader
- **ReaderViewModelTest** (pre-existing): Item loading, toolbar toggle, font/theme clamping, bookmarks
- **EpubStripHtmlTest** (pre-existing): Static `stripHtml()` Jsoup sanitization with 17 cases (script/style/iframe/SVG/comment/CDATA removal, 2MB cap)
- **Status**: Basic ViewModel and HTML sanitization covered; external-intent init, `EpubReaderViewModel` state machine (`openPublication`, `onLocatorChanged`, chapter retrieval), audiobook seek/skip remain untested. ✅ 60% COMPLETE.

#### feature/settings
- **SettingsViewModelTest** (pre-existing): Prefs emit, theme/speed/skip-duration clamps, logging toggle, vault observe/add/remove, scan progress
- **SafeCheckoutLinkTest** (pre-existing): URL validation (scheme allowlist, case-insensitivity, missing host)
- **StaticAddressesTest** ✅ **REWRITTEN**: Now uses Turbine assertions on `donationState` to verify `DonationState.NoMethod` (with fallback address) vs `DonationState.Error` state transitions. **Previously a false-confidence test** with `assertTrue(true)` and mock self-assertions — now provides real regression protection.
- **Status**: Donation state machine (`createDonationInvoice`, `pollUntilPaid`, `cancelDonation`), invoice polling transitions, and `hasAnySettledInvoice()` startup logic remain untested. ✅ 75% COMPLETE.

### 🔴 DEFERRED (Architectural dependencies complicate testing)

#### feature/library, feature/onboarding
- **Status**: ViewModel testing requires extensive DI/context setup. These have many dependencies (`MediaController`, `@ApplicationContext`, multiple use-cases) that complicate unit test mocking. Recommend:
  - Defer feature-level ViewModel tests to instrumented tests or integration-level testing
  - Alternatively, refactor ViewModels to extract pure logic functions (state machines, filtering, etc.) into testable core layers
  - Current feature-level testing in `PlayerViewModelTest`, `ReaderViewModelTest`, `SettingsViewModelTest` (pre-existing) demonstrate the pattern but require significant setup
- **Decision**: v0.3.0-alpha focuses on core unit tests (pure functions, data transformations, migrations) which provide immediate ROI. Feature ViewModel testing deferred to v0.4.0+ with dedicated refactoring budget.

#### core/logger
- **LibravaultLoggerTest** ✅ NEW: 6 tests for SharedPreferences `isEnabled` get/set, log file writing (when enabled/disabled), file rotation at 512KB threshold (archival to `.bak`), `readLogs()` and `clearLogs()`
- **Status**: Core logging logic complete; Logcat calls stubbed via static mock to avoid "not mocked" errors on JVM. ✅ 95% COMPLETE.

#### core/database
- **MigrationsTest** ✅ NEW: 3 tests for `MIGRATION_4_5` idempotent column-add via PRAGMA check (prevents "duplicate column" SQLiteException crash if fallbackToDestructiveMigration deployed updated schema before formal migration)
- **Status**: Migration idempotency now protected; other migrations (1→2, 2→3, 3→4) are schema-only with no conditional logic (no test value). ✅ 100% COMPLETE (logic-bearing migrations).

---

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

### High Value (Next Sprint Recommended)

1. **feature/player**: `play()`, `togglePlayPause()`, `seekTo()`, `setSpeed()`, bookmark CRUD, retry backoff
   - Estimated effort: 6–8 test methods, 2–3 hours
   - Payoff: Protects critical playback code path

2. **feature/reader**: External-intent init, `EpubReaderViewModel` (`openPublication`, `onLocatorChanged`, chapter retrieval), audiobook seek/skip
   - Estimated effort: 8–10 test methods, 3–4 hours
   - Payoff: EPUB state machine is complex and prone to race conditions (navigation + progress)

3. **feature/settings**: Donation state machine (`pollUntilPaid` with Processing/Settled/Expired branches), resume-on-startup, `hasAnySettledInvoice()` exception swallowing
   - Estimated effort: 5–6 test methods, 2 hours
   - Payoff: Prevents silent donation-polling failures

4. **core/storage** (Optional): `MetadataExtractor.extract()` for PDF (page count, thumbnail rendering) and audio (duration, cover extraction)
   - Estimated effort: 4–6 test methods, but requires mocking BitmapFactory; consider Robolectric
   - Payoff: Protects cover-art and duration extraction (user-visible)

### Medium Value (Future Maintenance)

- **feature/reader highlight logic extraction**: Extract `highlights.mapNotNull { ... }` from `EpubReaderScreen`'s `LaunchedEffect` into standalone `buildHighlightDecorations(highlights: List<HighlightEntity>): List<Decoration>` for pure JVM unit testing (see plan item #5)
- **core/tts**: Mock or stub TextToSpeech for basic utterance-splitting and generation-tracking tests (requires workaround for engine callbacks)
- **core/storage SAF wrappers**: Integration-level tests with mock ContentProvider; not unit-testable in isolation

### Low Value / Doc Debt

- Compose screens (LibraryScreen, PlayerScreen, ReaderScreen, SettingsScreen, OnboardingScreen): Defer to screenshot/instrumented tests and manual QA
- Room migrations 1→4: Schema-only (no conditional logic); validated by Room's own schema verification
- Hilt `@Module` classes: Pure DI configuration; validated by annotation processor and integration tests

---

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

As of 2026-08-17 — **overall 40.1%** (3,565 / 8,892 lines):

| Module | Coverage | | Module | Coverage |
|---|---:|---|---|---:|
| `core:logger` | 97.4% | | `feature:settings` | 47.2% |
| `core:vaultcrypto` 🔒 | 90.3% | | `feature:player` | 39.2% |
| `core:vaultstore` 🔒 | 83.4% | | `feature:vault` | 36.6% |
| `core:ui` | 77.8% | | `feature:reader` | 26.5% |
| `core:vaultcontent` 🔒 | 70.2% | | `core:tts` | 24.9% |
| `core:database` | 63.3% | | `feature:library` | 23.5% |
| `core:storage` | 50.1% | | `core:domain` | 17.2% |
| | | | `feature:onboarding` | 15.9% |
| | | | `app` | 8.9% |

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
| `jvm-tests.yml` | every PR, plus every push to `dev`/`main` | All JVM unit tests, plus `lint` |
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
