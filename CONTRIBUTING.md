# Contributing to Libravault

Thank you for your interest in contributing. Libravault is GPL-3.0 licensed and welcomes contributions that align with its core principles.

## Core principles

Every contribution should preserve these properties:

1. **No network access** — no `INTERNET` permission, no outbound connections
2. **No broad storage access** — Scoped Storage only, never `MANAGE_EXTERNAL_STORAGE`
3. **No tracking** — no analytics, telemetry, or remote crash reporting
4. **No mandatory account** — the app must work fully offline and without sign-in

Pull requests that compromise any of these principles will not be merged.

## Getting started

```bash
git clone git@github.com:libravault-xyz/libravault.git
cd libravault
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

## Branch strategy

- `main` — stable, always builds
- `dev` — integration branch for feature work
- `feature/your-feature` — individual feature branches off `dev`

## Code style

- Kotlin official style guide
- `./gradlew lint` must pass with no new warnings
- All new use cases must have unit tests
- All new ViewModels must have unit tests

## Submitting changes

1. Fork the repo
2. Create a branch off `dev`
3. Write tests for your changes
4. Run `./gradlew testDebugUnitTest lint` and confirm it passes
5. Open a pull request against `dev` with a clear description

## Reporting bugs

Open an issue at https://github.com/libravault-xyz/libravault/issues.
Include Android version, device model, and steps to reproduce.

## License

By contributing, you agree your changes will be licensed under GPL-3.0.

## Donate

If you find Libravault useful and want to support it, donation addresses are in the app (Settings > About > Support Development) and in the [README](README.md#Donate).
