# LibraVault Reader — Agent Guide

> Living rules for AI agents (Kilo, Claude Code, etc.) working in this repo.
> Edit this file, never `.kilo/` (gitignored scratch space).

## Required: always ship unit tests with code changes

Every non-trivial code change **must** include unit tests covering the new
behaviour or the regression risk. A change is not "done" until tests are
committed alongside it.

### What "non-trivial" means

Trivial changes (typos, comment fixes, build-script bumps, dependency
version-only edits) are exempt. Everything else needs tests, including:

- Bug fixes — at least one test that fails on the pre-fix code (regression
  guard) and one that exercises the happy path of the fix.
- New behaviour — branch coverage for each new code path (success / failure
  / boundary).
- Refactors that touch public/internal APIs — tests proving the contract
  is unchanged.
- New ViewModels, use cases, repositories, DAOs — full happy-path coverage.

### Choosing a test type

Pick the cheapest level that can actually observe the thing you are asserting.
Going lower is not thrift if the level cannot see the failure — that produces a
test which passes forever, which is worse than no test because it retires the
gap from the inventory.

| Level | Where | Use it for | Cost |
|---|---|---|---|
| Pure JVM (JUnit 5) | `src/test/` | Logic with no Android types: state derivation, parsing, crypto, mappers, validators | ~instant |
| Robolectric | `src/test/` | Android framework classes and Compose **semantics** — accessible names, roles, click actions, real `Window` flags | seconds |
| Roborazzi screenshot | `src/test/` + `src/test/screenshots/` | **Pixels**: clipping, overlap, theme application, anything you can only see | seconds |
| Instrumented (`androidTest`) | emulator, `ui-tests.yml` | Behaviour Robolectric only *pretends* to have. Keystore is the canonical case — its Robolectric shim has no `securityLevel`, no StrongBox | ~20 min, CI only |
| Firebase Test Lab | physical device | Real hardware: arm64 native libs, a real TEE, actual audio output | slow, quota'd |

Two traps that have each cost real time here:

- **A level that cannot observe the property.** A "touch target >= 48dp" test was
  written against Compose semantics and deleted: `touchBoundsInRoot` is already
  clamped to the minimum, so it could not fail. That property is a screenshot
  question, not a semantics one.
- **A label that exists but is useless.** A generic "every clickable has an
  accessible name" sweep passes on `"A-"`, `"A+"`, `"Show"`, `"Hide"`. Sweeps are
  a floor; pin the actual wording for controls where the name carries meaning.

### Prove a new test can fail

Before committing a test, make it fail on purpose — break the assertion, or
revert the fix it guards — and confirm you see red. Then restore.

This is not ceremony. Tests that passed while asserting nothing have been found
repeatedly in this repo, including: a Hilt-injected field overwritten before the
assertion ever read it; an assertion on a crash signature that never occurs;
a screenshot gate that compared nothing because verification was off by default.
Every one looked correct in review.

The rule matters most where you cannot casually re-run — instrumented tests, iOS
— because there a green run is the *only* signal you get, and a green run is not
evidence that a test observed anything.

### Coverage expectations

Coverage is measured (Kover, line coverage, debug variant) and reported per
module in every CI run's summary. Regenerate locally with:

```bash
./gradlew koverXmlReportDebug \
          :app:koverXmlReportFdroidDebug \
          :feature:settings:koverXmlReportFdroidDebug
python3 scripts/coverage-summary.py
```

- `core:vaultcrypto`, `core:vaultstore` and `core:vaultcontent` are **gated**:
  dropping more than 1pp below `scripts/coverage-baseline.json` fails the build.
  Raise the baseline in the same PR that raises coverage.
- Everything else is report-only **by design**. A repo-wide ratchet produces
  tests written to move a number rather than to catch a defect.
- A PR that adds behaviour should not *reduce* its module's coverage. If it
  does, say why in the PR description.

Do not chase the overall percentage. 58% of all uncovered lines are Compose UI;
the way that number moves is by testing what the screens decide (extract to
`internal` functions) and what they render (screenshots), not by rendering
screens and asserting nothing.

### Test conventions in this repo

- Framework: **JUnit 5** (`org.junit.jupiter:junit-jupiter-api:5.10.2`)
  with **MockK** for mocks and **Turbine** for Flow assertions.
- Test deps live in `testImplementation`; `testRuntimeOnly("…junit-jupiter-engine")`
  is also needed for the runner.
- For JVM tests to actually execute, the module's `build.gradle.kts` must
  apply **`de.mannodermaus.android-junit5`**. Core modules that ship tests
  need this plugin explicitly — applying only `libravault.android.library`
  leaves tests compiling but unrunnable. See `core/storage/build.gradle.kts`
  and `core/domain/build.gradle.kts` for the two patterns in use.
- Pure helpers (gate logic, mappers, format detection, validators) should
  be marked **`internal`** rather than `private` so they can be unit-tested
  directly without spinning up the surrounding coroutine / DI scope. This
  is the standard pattern (e.g. `LibraryScannerImpl.needsEnrichment`).
- Real filesystem checks belong in JUnit 5 tests via `@TempDir`. Don't mock
  `java.io.File`.
- Test files live at `src/test/kotlin/<mirror of main package path>/`.
  Class name = `<Subject>Test.kt`.
- Test method names are backtick Kotlin strings describing behaviour, e.g.
  ```kotlin
  @Test fun `EPUB stub with null cover and Unknown author is enriched`() = …
  ```

### Instrumented tests (`src/androidTest/`) — report via assertions, never logs

Instrumented tests run on an emulator, and **there is no local emulator on
the dev box**, so CI is the only feedback loop. That makes one detail
load-bearing:

- `connectedAndroidTest` does **not** carry instrumentation stdout into the
  Gradle log, and AGP's HTML report does not capture it either. `Log.i` and
  `println` from an instrumented test are **write-only** — the value goes
  nowhere you can read.
- Anything a test needs to communicate must travel in an **assertion
  message**, because that is what lands in the report `ui-tests.yml` uploads
  as an artifact. Retrieve it with:
  ```
  gh run download <run-id> --repo LibraVault/reader -n ui-test-results
  ```
- Corollary, and the reason this is in a doc rather than a comment: **a green
  instrumented run is not evidence that a test observed anything.** With no
  local loop you cannot casually check, so deliberately break each new
  assertion once and confirm CI turns red before trusting it. (A test that
  logged its result instead of asserting it did exactly this — passed,
  reported nothing, and cost a full CI round trip to notice.)

A module gaining its **first** `androidTest` source set also needs build
wiring the convention plugins do not supply — see `feature/reader` and
`core/tts` for working examples:

- `defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`
- `androidTestImplementation(libs.bundles.testing.instrumentation)`
- a `packaging.resources.excludes` entry if dependencies collide on
  `META-INF` paths. `:app` already excludes several, but **library modules
  inherit nothing from it**, and the collision only appears once the module
  packages its own test APK.

### CI test commands

The CI workflow at `.github/workflows/jvm-tests.yml` runs:

```
./gradlew testDebugUnitTest --continue
```

This will exercise every module's `testDebugUnitTest` task in order. A
locally green run is the bar for "ready to merge" — don't push code that
fails this locally without an explanation.

Instrumented tests are **not** part of that gate. `.github/workflows/ui-tests.yml`
runs `connectedDebugAndroidTest` on an emulator, and on PRs to `dev` it runs
**only when the PR carries the `needs-emulator` label** (it always runs
nightly, and on PRs to `main`). A PR adding or changing anything under
`src/androidTest/` should be labelled, or it gets no signal at all until the
next nightly run.

## Commit hygiene

- One concern per commit. A bug fix and its tests can be one commit if the
  tests are integral; a refactor and a behaviour change must be two.
- Commit message subject uses Conventional Commits (`fix(scope): …`,
  `feat(scope): …`, `chore: …`, `docs: …`).
- Never commit `local.properties`, keystores, `*.env`, generated `build/`
  output, or anything covered by `.gitignore`.

## Working in a worktree

Bug fixes and features go in their own worktree + branch:

```
git worktree add -b fix/<short-slug> ../<worktree-dir>
```

Keep `dev` clean — rebase feature branches onto `dev` before opening a PR.

## Agent-team pipeline (dev / qa / principal review)

Filed issues can flow through an autonomous dev-agent → qa-agent →
principal-review-agent pipeline before landing on a human's plate. See
[`docs/agent-team-pipeline.md`](docs/agent-team-pipeline.md) for the full
state machine, [`.github/agent-policy.yml`](.github/agent-policy.yml) for
what always requires a human merge, and `.claude/agents/` for each role's
persona. Every rule above in this file is binding on those agents too —
they don't get a lighter bar than a human contributor.

Editing one of the pipeline's own workflow files? Read
`docs/agent-team-pipeline.md`'s "`claude-code-action` gotchas" section
first — a `GH_TOKEN` env var alone does not control that action's git/gh
identity, and getting this wrong cost real debugging time.