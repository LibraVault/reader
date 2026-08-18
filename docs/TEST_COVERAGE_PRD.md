# PRD: Test Coverage & Test Infrastructure

**Status:** Draft · **Author:** Principal review pass, 2026-08-16 · **Base commit:** `7f4712a` (dev)
**Supersedes the metrics half of:** [`docs/TEST_PLAN.md`](TEST_PLAN.md) (self-declared stale)

---

## 1. Current state (measured, not asserted)

Everything below was measured against `dev` @ `7f4712a`, not taken from existing docs.

| Signal | Value |
|---|---|
| Android JVM tests executed | **870** (`./gradlew testDebugUnitTest …` — BUILD SUCCESSFUL, 8m11s local, 1–3 min in CI) |
| Android instrumented tests | **2 files** (`ReadiumIntegrationTest`, `PocketTtsAudioOutputTest`) |
| iOS unit tests | **320** test funcs / 4,692 LOC across 29 files |
| iOS UI tests | **19** test funcs / 413 LOC |
| Android main / test LOC | 21,327 / 12,236 |
| iOS source / test LOC | 9,718 / 5,105 |
| Coverage instrumentation | none at baseline; **added in Phase 1** (Kover + xccov) |

### Measured coverage (Phase 1 result, 2026-08-17)

First real coverage numbers this repo has ever had. Line coverage, debug variant,
via `./gradlew koverXmlReportDebug` + `scripts/coverage-summary.py`.

**Overall: 40.1%** (3,565 / 8,892 lines)

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

This confirms the shape the gap inventory predicted, and contradicts the deleted
metrics table point for point. The crypto core really is well covered
(`core:vaultcrypto` 90.3%); the UI and feature layer is thin. `core:domain` — which
`TEST_PLAN.md` published as "✅ 95%" — measures **17.2%**: the four use cases with
dedicated tests are at 100%, and roughly twenty one-line delegating use cases plus
the data-class layer are at 0%.

Read the low numbers as targeting information, not alarm. `app` at 8.9% is mostly
`LibravaultNavHost` and `MainActivity`, which Phase 4 addresses directly.

**iOS: 54.7%** (7,046 / 12,881 executable lines across 91 files), excluding
vendored sources (`SherpaOnnx.swift` and the ThirdParty/Argon2 C files).
Largest zero-coverage surfaces are `MarkdownReaderContent.swift` (612 lines),
`BookmarksSheet.swift` (226), `MermaidDiagramView.swift` (195),
`PocketTTSEngine.swift` (144) — all SwiftUI views and the TTS engine boundary,
matching the Android picture.

Per-module Android test counts (executed, incl. both flavors where applicable):

| Module | Tests | | Module | Tests |
|---|---:|---|---|---:|
| feature/reader | 117 | | feature/library | 32 |
| core/vaultcrypto | 94 | | core/ui | 28 |
| core/tts | 92 | | core/database | 24 |
| core/storage | 89 | | core/vaultcontent | 21 |
| feature/settings | 77 | | app | 13 |
| feature/vault | 71 | | core/logger | 9 |
| feature/player | 66 | | feature/onboarding | 4 |
| core/vaultstore | 65 | | core/domain | 46 |

**This is a healthy suite.** It is green, it is fast, `AGENTS.md` mandates tests with every change, and the crypto core is genuinely well covered (Wycheproof known-answer vector, tamper detection, nonce uniqueness, round-trip). The 15 Robolectric-hosted Compose tests in `src/test/` are a good call — they buy real UI assertions without emulator cost.

The problems are not "too few tests." They are **blind spots we cannot see, and gates that do not fire.**

---

## 2. Problem statement

Three structural failures, in severity order:

1. **We cannot measure coverage, so every coverage claim in the repo is unfalsifiable.** `docs/TEST_PLAN.md` publishes a metrics table with entries like "✅ 95%" and "🟡 70%". No JaCoCo, no Kover, no `-enableCodeCoverage`. Those numbers were estimated by hand and are now stale by four modules.
2. **Two of our three test gates have not run in months, or ever.** They are configured, documented, and dead.
3. **Android and iOS now ship two independent implementations of the same on-disk encrypted vault format, and nothing tests that they agree.** A vault written by one platform has never been proven to open on the other, in CI or otherwise.

---

## 3. Gap inventory

Severity: **S1** ships user data loss or a security regression · **S2** ships a user-visible functional regression · **S3** erodes confidence/velocity.

### S1 — Cross-platform vault format divergence is untested
`core/vaultcrypto` (Kotlin) and `ios/.../Sources/VaultCrypto` (Swift, merged today in #220) independently implement the same format. iOS `VaultCryptoTestSupport.swift` describes itself as centralising "what Android's" tests do — it is a *parallel re-implementation of the same test shapes*, not a shared vector. Both can be self-consistently wrong in the same way, or divergently wrong, and every existing test still passes.

No shared golden vault file exists anywhere in the repo (verified: no `*.vault`, vector, or fixture artifact shared between the trees).

### S1 — `Hkdf` has no known-answer test
`core/vaultcrypto/.../Hkdf.kt` (57 LOC) has **zero** test references. Its only caller is `FileContentKey.deriveKey`, which also has zero direct tests. It is exercised indirectly by the round-trip tests — which is exactly the failure mode that hides it: a wrong-but-deterministic HKDF round-trips perfectly on one platform and silently cannot open the other platform's vaults. AES-GCM has an RFC/Wycheproof KAT; HKDF has none.

Same gap mirrored on iOS: `VaultKeyMaterial` (142 LOC), `SecureZero`, `SecureRandom`, `BigEndianCoding` — all zero test references.

### S1 — Emulator UI tests have not run since 2026-04-24
`ui-tests.yml` triggers on `pull_request: branches: [main]`. All development targets `dev`. Last run: **2026-04-24**, ~114 days ago. `connectedDebugAndroidTest` has produced no signal for the entirety of the Markdown viewer, Encrypted Vaults, and iOS parity work.

### S1 — The TTS audio-output gate has never executed
`gh run list --workflow=android-tts-audio-test.yml` returns `[]`. This is the workflow built for issue #107 — the one whose underlying test proved Pocket TTS emitted no audio at all through v0.4.5-alpha. It is `workflow_dispatch`-only and has never been dispatched. The release-prep gate documented in `TEST_PLAN.md` is not a gate.

### S1 — `dev` has no branch protection
`GET /repos/:owner/:repo/branches/dev/protection` → 404 Branch not protected. No required status checks. Nothing server-side prevents merging a red PR. The agent pipeline's CI-wait is convention only — and that wait was itself deadlocked until PR #219 fixed it yesterday, meaning for some window auto-merge had no working CI gate *and* no backstop.

### S2 — Room migrations 1→2, 2→3, 3→4, 5→6 are untested
Six migrations exist; two are tested. `MIGRATION_4_5` is tested against a **mocked** `SupportSQLiteDatabase` asserting SQL substrings — it verifies the string looks right, not that SQLite accepts it. Only `MIGRATION_6_7` executes for real (Robolectric).

Compounding: `LibravaultDatabase` sets `exportSchema = true`, but no `room.schemaLocation` KSP argument is configured and no `schemas/` directory is checked in. Room's own schema-identity validation and `MigrationTestHelper` are both unavailable to us.

A schema-shape mistake in a rebuild-the-table migration throws on **every existing user's device on upgrade**.

### S2 — Security-behaviour code paths untested
- `SecureScreenEffect` / `FLAG_SECURE` — 5 call sites across the vault screens, zero tests. A regression silently allows screenshots and recents-thumbnail capture of decrypted vault content.
- `AndroidKeystoreHardwareKeyWrap` (165 LOC) — only the `FakeHardwareKeyWrap` is exercised. The real Keystore path has no coverage at any level.
- `VaultScreenSecurityPreference` is tested; the effect it drives is not.

### S2 — Highest-traffic UI surfaces untested
| Surface | LOC | Tests |
|---|---:|---|
| `LibraryScreen.kt` | 1,344 | 0 — largest Android file in the repo |
| `LibravaultNavHost.kt` | 247 | 0 — no navigation-graph test on either platform |
| `PlaybackService.kt` | 159 | 0 — foreground media service (the issue #12 bug class) |
| `ReaderComponents.kt` | 486 | 0 |
| `PlayerComponents.kt` | 537 | 0 |
| iOS `SherpaOnnx.swift` | 2,283 | 0 (vendored) |
| iOS `PocketTTSEngine.swift` | 181 | 0 |

### S3 — No visual, accessibility, or performance regression testing
- **Visual:** Compose/SwiftUI tests assert node existence, never appearance. `ColorSchemeContrastTest` is the only pixel-adjacent check.
- **Accessibility:** no TalkBack/VoiceOver traversal test, no touch-target-size assertions, no content-description audit. For a reading app this is a first-class user requirement, not a nice-to-have.
- **Performance:** no macrobenchmark module, no startup or scroll-jank baseline. Argon2id KDF cost on real hardware is an open, unmeasured follow-up.

### S3 — `TEST_PLAN.md` is stale and partly fabricated
Self-declares stale in its own header. Predates `core:vaultcrypto`, `core:vaultstore`, `core:vaultcontent`, `feature:vault`. Its metrics table cites "v0.3.0-alpha, ~77 unit tests" against an actual 870. Its "Future Testing Priorities" section lists work already completed. Actively misleading to both humans and the dev-agent, which reads repo docs as ground truth.

### S3 — Lint is not a real release gate
`./gradlew lint` runs in CI, but `app` sets `checkReleaseBuilds = false` with a baseline file. Release-configuration lint findings are never surfaced.

---

## 4. Goals / non-goals

**Goals**
- G1. Make coverage measurable and visible on both platforms; delete every unverifiable coverage claim.
- G2. Make every configured gate actually fire, and make `dev` structurally unmergeable when red.
- G3. Prove Android↔iOS vault format compatibility in CI, on every change to either crypto tree.
- G4. Close the S1/S2 correctness gaps listed above.
- G5. Establish accessibility and visual-regression baselines.

**Non-goals**
- A coverage *percentage* target. Ratchets breed assertion-free tests written to move a number. We gate on "coverage did not drop" and on named critical paths, not on a global threshold.
- 100% Compose/SwiftUI screen coverage. Screens stay behind Robolectric node tests plus screenshot baselines.
- Testing vendored third-party code (`SherpaOnnx.swift`, `third-party/`). We test **our** boundary to it.
- Adding any network dependency. Per standing project constraint, no test may introduce networking libs.

---

## 5. Requirements

| # | Requirement | Acceptance |
|---|---|---|
| R1 | Coverage is measured per-module on Android | Kover reports generated in CI, uploaded as an artifact, summarised in the PR |
| R2 | Coverage is measured on iOS | `-enableCodeCoverage YES`; `xccov` summary posted to the run |
| R3 | Coverage cannot silently regress | PR comment shows per-module delta vs. `dev`; a drop >1pp on a `core:vault*` module fails the job |
| R4 | Instrumented tests run on PRs that can reach users | `ui-tests.yml` fires on PRs to `dev`, or on a label, or nightly on `dev` — a decision is required (§7 open question) |
| R5 | The TTS audio gate runs before every release | Dispatched by `release.yml`, or scheduled weekly on `dev` |
| R6 | `dev` requires green CI to merge | Branch protection with required checks: JVM Tests, iOS Build & Test, Lint |
| R7 | A vault written on Android opens on iOS and vice versa | Checked-in golden vault fixture + a test on each platform that opens the *other* platform's artifact |
| R8 | HKDF is pinned to RFC 5869 vectors on both platforms | KAT test in `core:vaultcrypto` and in `LibraVaultTests` using identical vectors |
| R9 | Every Room migration executes against real SQLite | Robolectric test per migration; `room.schemaLocation` configured; `schemas/*.json` checked in |
| R10 | `FLAG_SECURE` behaviour is asserted | Robolectric test per vault screen asserting the window flag is set/cleared |
| R11 | Accessibility baseline exists | Traversal + touch-target + content-description tests on Library, Reader, Player, Vault screens on both platforms |
| R12 | `TEST_PLAN.md` reflects reality | Regenerated from measured numbers; a CI check flags it when module count changes |

---

## 6. Implementation plan

Six phases. Phases 1–2 are prerequisites for honest reporting and should land first. Phases can otherwise proceed in parallel; each is a separate issue and PR on a `test/*` or `fix/*` branch per the repo's branching rule.

### Phase 0 — Stop the bleeding ✅ DONE (PR #222, 2026-08-16)
*Highest value per hour in this entire document. All configuration, no test authoring.*

| Task | File |
|---|---|
| Retarget `ui-tests.yml` to PRs against `dev` (see §7 Q1) | `.github/workflows/ui-tests.yml` |
| Enable branch protection on `dev` with required checks | GitHub settings (manual, user action) |
| Dispatch `android-tts-audio-test.yml` once and record the result | manual |
| Replace `TEST_PLAN.md`'s metrics table with a pointer to this PRD | `docs/TEST_PLAN.md` |

**Exit:** every configured gate has fired at least once and is visible on the `dev` branch page.

### Phase 1 — Coverage instrumentation ✅ DONE (2026-08-17)
| Task | Detail |
|---|---|
| Add Kover to the convention plugin | `libravault.android.library` / `.application`; version via `libs.versions.toml` — never inline |
| Aggregate report task | `./gradlew koverHtmlReport koverXmlReport` at root |
| CI: generate, upload, summarise | extend `jvm-tests.yml`; artifact + `$GITHUB_STEP_SUMMARY` table |
| iOS: `-enableCodeCoverage YES` + `xccov view --report --json` summary | `ios-app-build.yml` |
| Coverage-delta gate for `core:vault*` | fail on >1pp drop |

**Note:** `build-logic/convention` carries duplicated version constants that must be updated alongside `gradle/libs.versions.toml` — a known trap in this repo.

**Exit:** a real, per-module coverage number exists for the first time. Re-baseline §1 of this document against it.

### Phase 2 — Cross-platform vault interop ✅ DONE (2026-08-17)
1. Add a `docs/vault-format/` golden fixture directory containing a small vault produced by the Android writer at format version 1, with a fixed passphrase, fixed salt, and fixed nonces (deterministic writer path already exists — `VaultStoreTest` uses `deterministicRandom`).
2. Android test: open the fixture, assert plaintext bytes.
3. iOS test: open the **same** fixture file, assert identical plaintext.
4. Reverse direction: an iOS-written fixture opened by Android.
5. Wire both into their existing CI jobs. Any change under either crypto tree must run both.

**Why a checked-in artifact rather than a shared vector list:** a vector list still lets both sides drift together if the list is regenerated from one implementation. A frozen binary artifact is the only thing that catches "we both changed the format identically."

### Phase 3 — Crypto & migration correctness ✅ DONE (2026-08-17)
| Task | Where |
|---|---|
| HKDF RFC 5869 KAT (test vectors 1–3) | `core:vaultcrypto`, `LibraVaultTests` |
| `FileContentKey` derivation determinism + domain separation | both platforms |
| `VaultKeyMaterial` direct tests | both platforms |
| iOS `SecureZero` / `SecureRandom` / `BigEndianCoding` | `LibraVaultTests` |
| Configure `room.schemaLocation`, check in `schemas/*.json` | `core/database/build.gradle.kts` |
| Real-SQLite Robolectric test per migration 1→2 … 5→6 | `core/database/src/test/` |
| Full-chain upgrade test: v1 DB migrated straight to v7 | `core/database/src/test/` |

`SecureZero` is worth a specific note: it is the kind of function a compiler is entitled to optimise away, and it is the difference between a key lingering in memory and not. Test it, and document what the test can and cannot prove.

### Phase 4 — Untested behaviour surfaces (3–5 days, 3 PRs, **S2**)
| Task | Approach |
|---|---|
| `FLAG_SECURE` assertions across all 5 vault screens | Robolectric, assert `WindowManager.LayoutParams.FLAG_SECURE` |
| `AndroidKeystoreHardwareKeyWrap` ✅ DONE (issue #253, PR #279) | instrumented test (`androidTest`) — Keystore is not Robolectric-faithful. `ui-tests.yml`'s `google_apis` x86_64 API 34 emulator reports `SECURITY_LEVEL_SOFTWARE`, so `create()`'s hardware-backed happy path (case 9) and same-alias key replacement (case 10) are untestable there; both need a real device (Firebase Test Lab, `android-tts-audio-test.yml`'s `akita`/Pixel 8a pipe) and are tracked as a follow-up. Cases 1–8 (round-trip, nonce non-reuse, tamper detection, missing-alias recovery, cross-vault isolation, key persistence, and the software-backed rejection path) run in the emulator job today. |
| `LibravaultNavHost` route/argument tests | Robolectric `TestNavHostController` |
| `PlaybackService` lifecycle + notification | Robolectric `ServiceController` |
| Extract testable logic from `LibraryScreen.kt` (1,344 LOC) | refactor pure state derivation into `internal` functions per `AGENTS.md`, then unit test |
| iOS `PocketTTSEngine` boundary | fake the `SherpaOnnx` seam; guard real audio calls behind an XCTest check (known CI hang) |

### Phase 5 — Accessibility & visual baselines (3–4 days, 2 PRs, **S3**)
| Task | Approach |
|---|---|
| Android a11y: content descriptions, touch targets ≥48dp, traversal order | Compose `assertIsDisplayed` + semantics assertions on Library/Reader/Player/Vault |
| iOS a11y: VoiceOver labels, Dynamic Type at largest size | XCUITest accessibility audit (`performAccessibilityAudit`, iOS 17+) |
| Screenshot baselines | Roborazzi (Android, JVM — no emulator) and iOS snapshot assertions; light + dark |
| Contrast: extend beyond the existing `ColorSchemeContrastTest` to component level | |

### Phase 6 — Documentation & guardrails (1 day, 1 PR)
- Regenerate `TEST_PLAN.md` from measured data; delete the fabricated metrics table permanently.
- Add a short "how to choose a test type" decision table (pure JVM / Robolectric / instrumented / Test Lab).
- Update `AGENTS.md` with the coverage-delta expectation so the dev-agent inherits it.
- Add a CI check that flags `TEST_PLAN.md` when the module list changes.

### Phase 7 — Make screens renderable, then render them (~5–8 days, 6 PRs, **S3**)

Scoped 2026-08-18, after Phases 0–6 landed and coverage settled at **41.6%**.

#### Why this is the next phase

Measured breakdown of every uncovered line in the repo:

| Category | Covered | Missed | Coverage | Share of all missed |
|---|---:|---:|---:|---:|
| **Compose UI** | 1,005 | **6,321** | **13.7%** | **63.3%** |
| Other | 3,020 | 1,410 | 68.2% | 14.1% |
| Logic/data | 1,623 | 1,353 | 54.5% | 13.6% |
| ViewModels | 1,515 | 895 | 62.9% | 9.0% |

Nearly two-thirds of the deficit is Compose screen bodies. Everything else is
55–68%, which is healthy. "Coverage is 41.6%" therefore means "the app is mostly
UI code and the UI is barely tested", not "the logic is untested".

#### The correction that defines this phase

The original plan was "point Roborazzi at the real screens" — cheap, since a
screenshot test executes the whole composable, so its lines count *and* it
catches visual regressions.

**That is not possible as the screens are written today.** Every screen except
`PlayerScreen` takes `viewModel: XViewModel = hiltViewModel()` and passes the
**ViewModel itself** down into its private sub-composables
(`NameStep(state, viewModel)`, `PinStep(state, viewModel)`, …). They cannot be
rendered in a test without a real ViewModel and a Hilt graph.

`PlayerScreen` is the exception — it was split into a thin Hilt wrapper plus
pure `PortraitPlayerContent(item, state, actions)` / `LandscapePlayerContent(…)`.
It is also, at **44.3%**, the best-covered feature module. That is not a
coincidence, and it is the template.

So "screenshot the screens" and "extract testable logic" are not two competing
levers — they are one program. The extraction is the prerequisite, and it
unlocks both kinds of test at once:

- pure unit tests over what the screen **decides** (the `LibraryScreenLogic`
  pattern: 100% covered, moved `feature:library` 23.5 → 26.4)
- screenshot baselines over what it **renders** (Roborazzi, landed in Phase 5)

#### Per-screen recipe

1. Split `XScreen(viewModel)` into a thin wrapper plus `XContent(state, actions)`
   — behaviour-preserving, no rendering restructured.
2. Evidence of preservation: the module's **existing tests pass unchanged**
   (the standard used in PR #252).
3. Screenshot `XContent` in Dark / Light / Sepia, inspecting every baseline
   before committing.
4. Unit-test any decision logic the split exposes.

#### Targets, by uncovered lines

| Screen | Missed | Now | Notes |
|---|---:|---:|---|
| `SettingsScreenKt` | 261 | 0% | Largest 0% screen; `TtsSettingsSectionTest` proves the pattern works in this module |
| `ReaderScreenKt` | 246 | 0% | |
| `CreateVaultScreenKt` | 167 | 0% | Security-adjacent — do after the pattern is settled |
| `EpubReaderScreenKt` | 149 | 0% | Heaviest state machine; may warrant logic extraction first |
| `PdfReaderScreenKt` | 146 | 0% | |
| `LibraryScreenKt` | 615 | 8.8% | **Sequence after PR #265**, which is splitting this file per-composable and is awaiting a human merge |

#### Honest projection

Getting these six to ~50% is roughly **+3–4pp overall**. Reaching ~60% repo-wide
would require Compose UI broadly at ~60% — a longer program than this phase.

**Do not treat the percentage as the goal.** Line coverage counts executed
lines, not asserted behaviour: this cycle produced five tests that executed code
and asserted nothing (a Hilt field overwritten before assertion; a crash
signature that never occurs; a touch-target check that could not fail; a probe
logging to a stream nobody reads; a screenshot gate comparing nothing). Coverage
would have scored every one of them as success. The four real bugs found —
HKDF's empty-salt RFC violation, `FLAG_SECURE` cleared mid-navigation, sepia
contrast below AA, and unusable TalkBack labels — came from asking what could be
silently wrong, not from chasing a number.

#### Risks

- **Baseline churn.** Every intentional UI change rewrites screenshots. Keep the
  set per screen small, and remember `src/test/screenshots` is in
  `agent-policy.yml` `sensitive_paths` — an agent must never bless a diff.
- **These are production UI refactors.** Each PR needs the existing-tests-pass
  evidence, and each is a candidate for a real behaviour change slipping in
  unnoticed.
- **`feature:library` collides with PR #265.** Do not start it until that lands.

---

## 7. Open questions

**Q1 — How should instrumented tests be scheduled?** `ui-tests.yml` at ~15–20 min per run on every PR to `dev` would be the slowest gate by an order of magnitude (JVM tests run in 1–3 min). Options:
- (a) On every PR to `dev` — maximum signal, slowest merges.
- (b) Nightly scheduled run on `dev` — cheap, but a regression can sit for a day.
- (c) On a `needs-emulator` label plus a pre-release run — targeted, relies on humans labelling correctly.

**Recommendation: (b) + (c).** Nightly catches drift; the label handles PRs that obviously touch instrumented surfaces; a mandatory pre-release run is the real gate. This preserves the fast merge loop the agent pipeline depends on.

**Q2 — Should the TTS audio test be scheduled or release-gated?** It consumes Firebase Test Lab physical-device quota. Release-gating is cheaper and catches the case that matters; weekly scheduling catches drift earlier. Recommendation: release-gated, with a manual dispatch retained.

**Q3 — Coverage thresholds beyond `core:vault*`?** Proposed: hard gate only on the crypto/vault modules initially, report-only elsewhere for one month, then revisit with real data.

---

## 8. Success metrics

| Metric | Baseline (2026-08-16) | Target |
|---|---|---|
| Modules with measured coverage | 0 / 16 | **16 / 16 ✅** |
| Gates that have fired in the last 30 days | 2 of 4 | **4 of 4 ✅** |
| Cross-platform vault interop tests | 0 | **8 ✅** (4 Kotlin + 4 Swift, one frozen fixture) |
| Room migrations with real-SQLite execution | 1 of 6 | **6 of 6 ✅** (full v1→v7 chain) |
| S1 gaps open | 5 | **0 ✅** |
| Untested files >150 LOC (Android, non-Compose) | 8 | ≤2, each with a documented reason |
| `dev` requires green CI | no | **yes ✅** |

---

## 9. Risks

| Risk | Mitigation |
|---|---|
| Kover in the convention plugin breaks the build for all 16 modules at once | Land on one module first, then roll out |
| Emulator CI reintroduces the flakiness that got it sidelined | Nightly (non-blocking) rather than per-PR; `--retry` on known-flaky targets |
| Golden vault fixture must never change silently | Mark the directory in `.github/agent-policy.yml` as a sensitive path requiring human review |
| Screenshot tests become a maintenance tax | Limit to 4 screens × 2 themes; require an explicit re-baseline commit |
| Coverage gate blocks legitimate refactors that delete tested code | Gate on `core:vault*` only, and allow an explicit override label |

---

## 10. Effort summary

| Phase | Effort | Severity addressed |
|---|---|---|
| 0 — Stop the bleeding ✅ | ½ day | S1 ×3 |
| 1 — Coverage instrumentation ✅ | 1–2 days | S3 (unblocks all measurement) |
| 2 — Cross-platform vault interop ✅ | 2–3 days | S1 |
| 3 — Crypto & migration correctness ✅ | 2–3 days | S1, S2 |
| 4 — Untested behaviour surfaces | 3–5 days | S2 |
| 5 — Accessibility & visual baselines | 3–4 days | S3 |
| 6 — Documentation & guardrails | 1 day | S3 |
| **Total** | **~13–19 days** | |

Phase 0 alone closes three S1 gaps in half a day and should not wait on the rest.
