# LibraVault iOS Development Worktree

This is a separate Git worktree for iOS v3.0 development (`feat/v3-ios-port` branch). It allows you to work on iOS features without interfering with Android development in the main `reader/` worktree.

## Setup

The worktree was created automatically when you ran:
```bash
git worktree add ../reader-ios feat/v3-ios-port
```

From the main worktree, you can see both:
```bash
cd /home/rob/git/LibraVault/reader      # Android/main development
cd /home/rob/git/LibraVault/reader-ios  # iOS development (this worktree)
```

## Working in this Worktree

### Branch & Git Operations

```bash
# You're already on feat/v3-ios-port
git status
git log --oneline

# Create a feature branch from this worktree
git checkout -b feat/v3-ios-core-domain
# ... make changes ...
git add ...
git commit -m "..."
git push origin feat/v3-ios-core-domain

# Create a PR against dev or feat/v3-ios-port
```

### Building

```bash
# Build KMP frameworks
./gradlew :core:domain:assembleIosSimulatorArm64MainKlibrary

# Build iOS app for simulator
xcodebuild build \
  -workspace ios/LibraVault.xcworkspace \
  -scheme LibraVault \
  -configuration Debug \
  -destination 'generic/platform=iOS Simulator'

# Run tests
./gradlew test  # KMP tests
xcodebuild test -workspace ios/LibraVault.xcworkspace -scheme LibraVault
```

See `docs/v3-ios-ci-setup.md` for full build instructions and troubleshooting.

## CI/CD

Three GitHub Actions workflows run automatically:

1. **`ios-build.yml`** — Triggered on push/PR to `dev`, `feat/v3-ios-port`, `main`
   - Builds KMP frameworks, iOS app, runs tests, checks architecture compliance
   - ~15–25 minutes total

2. **`ios-checks.yml`** — Triggered on PRs modifying `ios/**`
   - Privacy denylist check (no firebase, sentry, etc.)
   - Verifies iCloud/Keychain are not used
   - Enforces architecture decisions

3. **`ios-release.yml`** — Triggered on git tags `v3.*`
   - `v3.0.0-beta.1` → uploads to TestFlight
   - `v3.0.0` → uploads to App Store Connect

Full details: `docs/v3-ios-ci-setup.md`

## Architecture Overview

### Modules in KMP (shared between Android & iOS)

- `core/domain` — Domain models, use cases, repositories (interfaces)
- `core/database` — SQLite schema, DAOs, migrations (Room KMP)
- `core/storage` — File scanning, metadata extraction (interfaces + platform `actual`s)
- `core/tts` — Text-to-speech interface (iOS implementation deferred to v3.1)
- `core/logger` — Local-only logging interface

### iOS-Only Modules

- `ios/LibraVault/` — Xcode project
  - `DesignSystem/` — SwiftUI theme (ports `core/ui`)
  - `Features/` — Onboarding, Library, Reader, Player, Settings screens
  - `Persistence/` — App DI container, Room KMP wiring
  - `Platform/` — iOS platform code (UIDocumentPicker, security-scoped bookmarks, etc.)
  - `UITests/` — 3 smoke tests

### Android Modules (Unchanged)

- `app/`, `feature/*` — Android-only; Hilt DI, Compose UI
- Shares `core/domain`, `core/database`, `core/storage` KMP modules

## Privacy & Security Constraints

This project has strict privacy requirements (§4.10 of the iOS PRD):

- ❌ No iCloud, no Keychain, no CloudKit
- ❌ No analytics SDKs (firebase, sentry, amplitude, mixpanel, etc.)
- ❌ No telemetry, tracking, or crash reporting
- ✅ All state lives in the app sandbox
- ✅ Logging is local-only

The `ios-checks.yml` workflow enforces these. If you need to add a dependency, verify it's not on the denylist first.

## Phase Progress

See `.kilo/plans/1783921330630-ios-port-prd.md` for full details.

- **Phase A (KMP foundation)** — Convert Android modules to KMP; build Kotlin/Native frameworks
- **Phase B (iOS skeleton)** — Xcode project, SwiftPM, persistence layer, file scanning
- **Phase C (feature screens)** — Onboarding, Library, Reader, Player, Settings, UI tests
- **Phase D (release)** — App Store privacy label, TestFlight beta, App Store submission

Each phase has explicit exit criteria that must pass before moving on.

## Common Tasks

### Run the CI checks locally before pushing

```bash
# Lint & security checks (same as ios-checks.yml)
grep -i "firebase\|sentry\|amplitude" ios/Package.swift
grep -r "NSUbiquitousKeyValueStore\|CloudKit\|SecItemAdd" ios/LibraVault/ | grep -v Tests
grep -r "NSURLIsExcludedFromBackupKey" ios/LibraVault/Persistence/
```

### Update the iOS PRD plan

Edit `.kilo/plans/1783921330630-ios-port-prd.md` and commit with the plan revisions.

### Sync with Android changes

The KMP modules (`core/domain`, `core/database`, `core/storage`, etc.) are shared. If Android work merges changes to these modules:

1. Rebase this worktree on `dev`:
   ```bash
   git fetch origin dev
   git rebase origin/dev
   ```

2. Rebuild KMP frameworks:
   ```bash
   ./gradlew clean :core:domain:assembleIosSimulatorArm64MainKlibrary
   ```

3. Rebuild iOS app:
   ```bash
   xcodebuild build -workspace ios/LibraVault.xcworkspace -scheme LibraVault
   ```

## Removing the Worktree

If you need to remove this worktree later:

```bash
# From the main reader/ worktree
cd /home/rob/git/LibraVault/reader
git worktree remove ../reader-ios

# This removes the working directory but keeps the branch (feat/v3-ios-port)
# To re-create it later:
git worktree add ../reader-ios feat/v3-ios-port
```

## Links

- **iOS PRD:** `.kilo/plans/1783921330630-ios-port-prd.md`
- **CI/CD Setup:** `docs/v3-ios-ci-setup.md`
- **Git Worktree Docs:** https://git-scm.com/docs/git-worktree
- **GitHub Actions for iOS:** https://developer.apple.com/documentation/xcode/building-from-the-command-line

