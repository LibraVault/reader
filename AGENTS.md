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

### CI test commands

The CI workflow at `.github/workflows/jvm-tests.yml` runs:

```
./gradlew testDebugUnitTest --continue
```

This will exercise every module's `testDebugUnitTest` task in order. A
locally green run is the bar for "ready to merge" — don't push code that
fails this locally without an explanation.

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