# iOS v3.0 CI/CD Setup Guide

> **⚠️ STATUS CORRECTION (2026-07-25):** This document describes `ios-build.yml`, `ios-release.yml`, and `ios-checks.yml` — none of which exist in this repo. They were planned but never implemented. The workflows that actually exist and work are `ios-app-build.yml` and `ios-testflight.yml`. For the real, verified-working release process, see **[iOS-TESTFLIGHT-RELEASE-PROCESS.md](iOS-TESTFLIGHT-RELEASE-PROCESS.md)**. This doc is kept for its architectural ideas (privacy/dependency-denylist CI checks, tag-based release tracks) in case they're worth building later, but nothing below should be treated as current state.

This document describes the GitHub Actions workflows for iOS development and release. All workflows run on **macOS 14 runners** and assume KMP modules are built first before iOS Xcode builds.

## Workflows Overview

### 1. `ios-build.yml` — Continuous Integration (PR & main builds)

**Triggers:** Push to `dev`, `feat/v3-ios-port`, `main`; pull requests to these branches.

**Jobs (run in parallel where possible):**

- **kmp-build** — Compiles Kotlin/Native frameworks for iOS Arm64, Simulator Arm64, and X86_64
  - Builds all core modules to `.klib` artifacts
  - Runs common KMP tests (shared domain logic)
  - Runs for ~5–8 minutes

- **android-regression** — Ensures iOS changes don't break Android
  - Runs `./gradlew assembleDebug` to verify APK still builds
  - Runs full Android test suite
  - Runs for ~3–5 minutes

- **xcode-build** — Builds the iOS app for simulator (requires kmp-build to finish)
  - Resolves SPM dependencies
  - Builds with Xcode for iOS Simulator
  - No code signing (simulator-only)
  - Runs for ~3–5 minutes

- **ios-unit-tests** — Unit tests for iOS platform code (requires kmp-build)
  - Runs on iPhone 15 simulator
  - Captures test results as artifact
  - Runs for ~2–4 minutes

- **ios-ui-tests** — Smoke tests: onboarding, library→reader, playback+Control Center (requires kmp-build)
  - 3 high-value smoke tests per PRD §4.12
  - Captures results as artifact on failure
  - Runs for ~5–10 minutes

- **lint-and-security** — Architecture & privacy checks
  - Scans `Package.swift` for denied dependencies (firebase, sentry, analytics SDKs, etc.)
  - Verifies `NSURLIsExcludedFromBackupKey` configuration
  - Runs for ~1 minute

- **summary** — Aggregate status (always runs, even if previous jobs fail)
  - Reports pass/fail for each job
  - Used by GitHub branch protection rules

**Total CI time:** ~15–25 minutes

**Example success output:**
```
✅ KMP build succeeded
✅ Android regression OK
✅ Xcode build succeeded
✅ iOS unit tests passed
✅ iOS UI tests passed (3/3)
✅ No denied dependencies found
✅ All checks passed
```

---

### 2. `ios-release.yml` — TestFlight & App Store Release

**Triggers:** Push of a git tag matching `v3.*` (e.g., `v3.0.0`, `v3.0.0-beta.1`)

**Tag format:**
- **Stable releases:** `v3.0.0`, `v3.1.0`, etc.
- **Beta/RC:** `v3.0.0-beta.1`, `v3.0.0-rc.2`, etc.
- Any tag matching `v3.*-beta*` or `v3.*-rc*` is uploaded to **TestFlight** (invite-only external beta)
- Any tag matching `v3.X.X` (no pre-release suffix) is uploaded to **App Store** (production review)

**Jobs:**

- **validate-tag** — Parses version and determines release track
  - Extracts version from tag (e.g., `3.0.0` from `v3.0.0`)
  - Marks as beta if tag contains `-beta` or `-rc`
  - Outputs: `version`, `is-beta` flags

- **kmp-build** — Builds release-optimized KMP frameworks
  - Same as CI workflow, but for release

- **build-and-sign** — Code signs and archives the iOS app
  - Requires secrets:
    - `APPLE_DISTRIBUTION_CERT` — base64-encoded `.p12` distribution certificate
    - `APPLE_DISTRIBUTION_CERT_PASSWORD` — password for the `.p12`
    - `KEYCHAIN_PASSWORD` — temporary keychain password
    - `PROVISIONING_PROFILE_BASE64` — base64-encoded `.mobileprovision`
  - Creates temporary keychain for signing (cleaned up after)
  - Exports IPA
  - Uploads IPA as GitHub artifact (7-day retention)

- **upload-testflight** — Uploads to TestFlight (only for beta/RC tags)
  - Requires secrets:
    - `APP_STORE_CONNECT_KEY_ID` — API key ID
    - `APP_STORE_CONNECT_ISSUER_ID` — issuer ID
    - `APP_STORE_CONNECT_KEY` — base64-encoded `.p8` private key
  - Uses `xcrun altool` to upload IPA
  - TestFlight immediately processes the build (~5–10 min)

- **submit-app-store** — Uploads to App Store for review (only for stable releases)
  - Same as TestFlight, but marks the build as production review
  - Build will appear in App Store Connect for manual submission

- **create-release** — Creates GitHub Release with IPA artifact attached
  - Generates release notes
  - Attaches IPA as downloadable file
  - Marks as pre-release if beta (for TestFlight link)

**Example workflow for v3.0.0-beta.1:**
```bash
git tag -a v3.0.0-beta.1 -m "v3.0.0 beta 1"
git push origin v3.0.0-beta.1
# → Builds, signs, uploads to TestFlight
# → GitHub Release created with TestFlight link in README
```

**Example workflow for v3.0.0 (production):**
```bash
git tag -a v3.0.0 -m "v3.0.0"
git push origin v3.0.0
# → Builds, signs, uploads to App Store Connect
# → GitHub Release created
# → Manual App Store submission required in App Store Connect
```

---

### 3. `ios-checks.yml` — Architecture & Privacy Compliance

**Triggers:** Pull requests that modify files under `ios/**` or this workflow itself.

**Jobs:**

- **dependency-denylist** — Fails if a denied dependency is added
  - Denied list: firebase, sentry, bugsnag, amplitude, mixpanel, analytics, tracking, telemetry, etc.
  - Scans `ios/Package.swift` and `ios/Podfile`
  - Enforces §4.9 of PRD (privacy-first posture)

- **privacy-configuration** — Audits privacy settings
  - Verifies `NSURLIsExcludedFromBackupKey` on database (excludes from iCloud backup)
  - Searches for iCloud APIs (`NSUbiquitousKeyValueStore`, `CloudKit`, etc.)
  - Searches for Keychain APIs (explicitly forbidden per §4.10)
  - Verifies bookmarks stored in app sandbox plist, not Keychain
  - Checks CFBundleDocumentTypes for EPUB+PDF (not audio)

- **architecture-decisions** — Ensures architectural choices are maintained
  - Verifies KMP module structure exists (Phase A5 requirement)
  - Checks for direct file system scanning (should use UIDocumentPickerViewController)
  - Verifies Readium-swift version is pinned

- **logging-audit** — Ensures logging is local-only
  - Verifies `IosLogger.swift` writes to app sandbox
  - Checks for absence of remote logging (HTTP, URLSession, etc.)

- **summary** — Reports overall compliance

**Example failure scenario:**
```
PR: Add Firebase Crashlytics for error reporting

❌ dependency-denylist: Firebase SDK found in Package.swift
   Privacy-first posture (§4.9): analytics SDKs are denied
   See docs/v3-ios-roadmap.md

This PR cannot be merged until Firebase is removed.
```

---

## Local Workflow Testing

### Run iOS build locally (macOS only)

```bash
cd /home/rob/git/LibraVault/reader-ios

# Build KMP frameworks
./gradlew :core:domain:assembleIosSimulatorArm64MainKlibrary

# Run KMP tests
./gradlew :core:domain:allTests

# Build iOS app for simulator
xcodebuild build \
  -workspace ios/LibraVault.xcworkspace \
  -scheme LibraVault \
  -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGN_IDENTITY="" \
  CODE_SIGNING_REQUIRED=NO

# Run iOS unit tests
xcodebuild test \
  -workspace ios/LibraVault.xcworkspace \
  -scheme LibraVault \
  -configuration Debug \
  -destination 'generic/platform=iOS Simulator,name=iPhone 15' \
  CODE_SIGN_IDENTITY="" \
  CODE_SIGNING_REQUIRED=NO

# Run iOS UI tests
xcodebuild test \
  -workspace ios/LibraVault.xcworkspace \
  -scheme LibraVaultUITests \
  -configuration Debug \
  -destination 'generic/platform=iOS Simulator,name=iPhone 15' \
  CODE_SIGN_IDENTITY="" \
  CODE_SIGNING_REQUIRED=NO
```

### Run privacy checks locally

```bash
# Dependency denylist
grep -i "firebase\|sentry\|amplitude" ios/Package.swift ios/Podfile

# NSURLIsExcludedFromBackupKey
grep -r "NSURLIsExcludedFromBackupKey" ios/LibraVault/Persistence/

# iCloud/Keychain usage
grep -r "NSUbiquitousKeyValueStore\|CloudKit\|SecItemAdd" ios/LibraVault/ | grep -v Tests
```

---

## GitHub Secrets Configuration

### For `ios-build.yml` (no secrets needed for CI)
No secrets required — simulator builds use no code signing.

### For `ios-release.yml` (required for TestFlight & App Store)

**1. Apple Distribution Certificate (`APPLE_DISTRIBUTION_CERT`)**
```bash
# Export .p12 from Keychain
# In Keychain.app:
#   1. Find "Apple Distribution: [Team]" certificate
#   2. Right-click → Export
#   3. Save as ~/LibraVault.p12 (set password)
#
# Base64 encode
base64 -i ~/LibraVault.p12 | pbcopy

# Add to GitHub Secrets:
# Name: APPLE_DISTRIBUTION_CERT
# Value: <paste base64>
```

**2. Apple Distribution Certificate Password (`APPLE_DISTRIBUTION_CERT_PASSWORD`)**
```bash
# Same password you set when exporting the .p12
# Name: APPLE_DISTRIBUTION_CERT_PASSWORD
# Value: <password>
```

**3. Keychain Password (`KEYCHAIN_PASSWORD`)**
```bash
# Temporary password for the GitHub Actions runner's temporary keychain
# Generate a random password:
openssl rand -base64 32 | pbcopy

# Name: KEYCHAIN_PASSWORD
# Value: <random password>
```

**4. Provisioning Profile (`PROVISIONING_PROFILE_BASE64`)**
```bash
# Download from Apple Developer Portal:
#   1. Log in to developer.apple.com
#   2. Certificates, Identifiers & Profiles
#   3. Profiles → LibraVault (or App Store)
#   4. Download .mobileprovision
#
# Base64 encode
base64 -i ~/LibraVault.mobileprovision | pbcopy

# Name: PROVISIONING_PROFILE_BASE64
# Value: <paste base64>
```

**5. App Store Connect API Key (`APP_STORE_CONNECT_KEY_ID`, `APP_STORE_CONNECT_ISSUER_ID`, `APP_STORE_CONNECT_KEY`)**
```bash
# Generate API key in App Store Connect:
#   1. Users and Access → API Keys
#   2. Generate new key with App Manager role
#   3. Download the .p8 file
#
# Secrets:
# Name: APP_STORE_CONNECT_KEY_ID
# Value: <Key ID, e.g., ABC123DEF>

# Name: APP_STORE_CONNECT_ISSUER_ID
# Value: <Issuer ID, e.g., 12345678-1234-1234-1234-123456789012>

# Base64 encode the .p8
base64 -i AuthKey_*.p8 | pbcopy
# Name: APP_STORE_CONNECT_KEY
# Value: <paste base64>
```

---

## Troubleshooting

### KMP build fails with "framework not found"
- Ensure `./gradlew clean` was run before the build
- Check that all Kotlin/Native caches are cleared: `rm -rf ~/.konan/cache`
- Verify Java version: `java -version` should show 17+

### Xcode build fails with "Code Sign error"
- In CI: Verify code signing is disabled (CI builds should have `CODE_SIGN_IDENTITY=""`)
- For local testing: Ensure Xcode has an active signing identity or disable it

### iOS UI tests timeout
- May happen if the simulator is slow to boot
- Increase timeout in workflow (add `-timeout 120` to xcodebuild test command)

### TestFlight upload fails with "401 Unauthorized"
- Verify `APP_STORE_CONNECT_KEY_ID`, `APP_STORE_CONNECT_ISSUER_ID`, and `APP_STORE_CONNECT_KEY` are set correctly
- Check API key has "App Manager" role (not just "Developer")
- Verify `.p8` file is not expired

### Privacy check fails on a legitimate use of iCloud/Keychain
- Edit `ios-checks.yml` to allow the exception
- Document the exception in a comment
- Open an issue to revisit the constraint

---

## CI Integration with Xcode Cloud (Optional Future)

If this project later adopts Xcode Cloud:
- Workflows can be migrated to `xcode-build-config.yml`
- The same KMP/Gradle steps must run first (Gradle is macOS/Linux only)
- Xcode Cloud handles signing & provisioning automatically
- TestFlight upload is automatic on every successful build

For now, GitHub Actions provides sufficient control.

---

## References

- [Xcode building from command line](https://developer.apple.com/documentation/xcode/building-from-the-command-line)
- [xcrun altool - App Store Connect API](https://help.apple.com/app-store-connect/#/dev82a6faf1a)
- [Notarizing macOS software before distribution](https://developer.apple.com/documentation/macos/notarizing_macos_software_before_distribution) (not needed for iOS)
- [GitHub Actions: macOS runners](https://docs.github.com/en/actions/using-github-hosted-runners/about-github-hosted-runners/about-github-hosted-runners#supported-runners-and-hardware-resources)

