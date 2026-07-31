# iOS TestFlight Release Process

**Status: Working, verified 2026-07-25.** This is the only accurate iOS CI/release document in this repo — everything else under `docs/` describing iOS Phase D, KMP framework linking, or `ios-build.yml`/`ios-release.yml`/`ios-checks.yml` describes a **different, unimplemented architecture** and should not be followed. See "Relationship to the Phase D KMP docs" at the bottom.

## What this actually is

LibraVault iOS builds and uploads to TestFlight entirely through GitHub Actions — no local Mac build is required for a release. The library is real vault-scanned data only (`LibraryFileScanner`) — the hardcoded demo library that used to back a no-vault first launch has been removed. There is no real Kotlin domain-layer integration yet; real EPUB/PDF/audio content parsing (chapter text, TTS, audiobook playback) is tracked separately — see the "Remove mockup books from the iOS app" plan.

Two workflows, both in `.github/workflows/`:

| Workflow | Trigger | Purpose |
|---|---|---|
| `ios-app-build.yml` | Automatic — every push/PR to `dev` or `feat/v3-ios-port` | Fast feedback: Simulator build + unit tests, no signing. Also builds Android core modules. |
| `ios-testflight.yml` | **Manual only** — `workflow_dispatch` | Full signed archive, export, and upload to TestFlight. Deliberately not automatic — TestFlight versions are gated by a human decision, not every commit. |

## Architecture

The iOS app is a real Xcode App target, not a SwiftPM library:

```
ios/LibraVaultApp/LibraVault/
├── LibraVault.xcodeproj          ← the actual project CI builds
├── LibraVault/
│   ├── Sources/                  ← App, KmpInterop, Models, Features (SwiftUI code)
│   └── Assets.xcassets/          ← includes AppIcon (1024×1024, currently an
│                                    upscaled placeholder from the Android icon)
├── LibraVaultTests/              ← DomainBridgeTests.swift (real smoke tests)
└── LibraVaultUITests/
```

There used to be a separate `ios/LibraVault/Package.swift` (SwiftPM library) — **it's gone**. It only ever declared a `.library` product, and SwiftPM has no iOS-app product type, so nothing built from it was ever installable or TestFlight-submittable. The `Sources/`/`Tests/` it pointed at were moved into the real Xcode target above; the dead `Package.swift` was deleted 2026-07-25 once this was confirmed.

## Triggering a TestFlight build

```bash
gh workflow run ios-testflight.yml --repo LibraVault/reader --ref <branch>
```
or via GitHub's UI: Actions → "iOS TestFlight Build & Upload" → Run workflow.

Takes ~5 minutes end to end (archive + export + upload). Apple then takes 15–30 minutes to process the build before it's visible in App Store Connect → TestFlight.

## Required GitHub secrets

All under `LibraVault/reader` repo settings:

| Secret | What it is |
|---|---|
| `APPLE_BUNDLE_ID` | `xyz.libravault.ios` |
| `APPLE_TEAM_ID` | Apple Developer Team ID (`H74LNL8UCG`) |
| `APPLE_DISTRIBUTION_CERT_BASE64` | Base64-encoded `.p12` — **must be exported from Keychain Access's "My Certificates" category** (not "Certificates" — see gotcha below), format "Personal Information Exchange (.p12)" |
| `APPLE_DISTRIBUTION_CERT_PASSWORD` | The password set when exporting that `.p12` |
| `PROVISIONING_PROFILE_BASE64` | Base64-encoded `.mobileprovision`, App Store Connect distribution type, matching the bundle ID and distribution cert above |
| `APP_STORE_CONNECT_ISSUER_ID` | From App Store Connect → Users and Access → Integrations → App Store Connect API |
| `APP_STORE_CONNECT_KEY_ID` | Same page, the specific key's ID |
| `APP_STORE_CONNECT_PRIVATE_KEY` | Raw contents of the downloaded `AuthKey_*.p8` file (App Manager role) |

Verify what's currently set (values are never readable, only names):
```bash
gh secret list --repo LibraVault/reader
```

## How the pipeline works (`ios-testflight.yml`)

1. **Checkout**
2. **Set up Xcode 26.5** on a `macos-26` runner — this specific version matters, see "Why macos-26" below
3. **Import Code Signing Certificate** — decodes the `.p12`, creates a throwaway keychain, imports with the real password
4. **Import Provisioning Profile** — decodes the `.mobileprovision`, extracts its UUID (`security cms -D` + `plutil`), installs it under `~/Library/MobileDevice/Provisioning Profiles/<UUID>.mobileprovision`, and exports that UUID via `$GITHUB_ENV` for later steps
5. **Build & Archive** — `xcodebuild archive` against `ios/LibraVaultApp/LibraVault/LibraVault.xcodeproj`, scheme `LibraVault`, manual signing, **matched by UUID, not by profile name** (see gotcha below)
6. **Create Export Options** — generates `ExportOptions.plist` (method `app-store-connect`, manual signing, same UUID)
7. **Export IPA** — `xcodebuild -exportArchive` → `build/export/LibraVault.ipa`
8. **Upload to TestFlight** — `fastlane pilot upload`, authenticated via a JSON file built with `jq` embedding the **actual PEM key content** under a `"key"` field (not a file path — see gotcha below)
9. Artifacts and a PR/issue comment with status

## Gotchas that cost real debugging time — don't repeat these

These aren't hypothetical risks, they're bugs that were actually hit and fixed on 2026-07-24/25. Worth reading before touching this pipeline.

**Apple raised the minimum SDK requirement to iOS 26.** Builds made with an older SDK (was iOS 18.2 / Xcode 15.3) are rejected by App Store Connect at validation time with "SDK version issue." This is why the runner is `macos-26` with Xcode 26.5, not `macos-14`/Xcode 15.3 like older iOS workflows in this repo assume. If your local Mac can't run a new enough Xcode (e.g. capped by an older macOS version), that's fine — build via this CI pipeline instead of locally.

**Keychain Access's certificate export dialog defaults to `.cer`, not `.p12`, and gives no error explaining why.** If you select an item under "Certificates" (not "My Certificates") and the "File Format" dropdown shows "Certificate (.cer)" with "Personal Information Exchange (.p12)" greyed out, you've selected the bare certificate with no private key attached — exporting produces a public-cert-only file that will fail keychain import in CI with `MAC verification failed (wrong password?)`, which reads like a password problem but isn't one. Fix: export from **"My Certificates"** category specifically, which bundles cert+key as one "identity."

**`PROVISIONING_PROFILE_SPECIFIER` matches against the profile's internal `Name` field, not the filename you gave it or the name you typed when creating it on the portal.** These don't always match exactly. Match by **UUID** instead (extracted from the profile itself via `security cms -D`) — that's what this workflow does and it's unambiguous.

**fastlane's `--api_key_path` JSON expects the private key content under a `"key"` field, not a `"key_filepath"` reference to a file on disk.** `key_filepath` is only valid when passing key info as Ruby action parameters, not in this static JSON format. Get this wrong and fastlane fails with `App Store Connect API key JSON is missing field(s): key`. Fixed by using `jq --rawfile key AuthKey.p8` to embed the actual PEM content with correct JSON escaping.

**Xcode's automatic signing can get stuck and report misleading errors.** It's normal to hit `"Communication with Apple failed: Your team has no devices..."` when the real problem is something else entirely (in the case that happened here, a certificate whose private key existed on Apple's servers but not in local Keychain). If automatic signing won't resolve after a `security find-identity -v -p codesigning` confirms a valid local identity exists, switch to **manual signing** with an explicitly downloaded provisioning profile rather than continuing to fight the automatic resolver.

## Post-upload: inviting testers

Once Apple finishes processing (15–30 min after upload):
1. https://appstoreconnect.apple.com/apps/6793972265/testflight/ios
2. TestFlight → Internal Testing → add testers by Apple ID email

## Known limitations of the current build

- **No real KMP `core:domain` integration.** The library itself is real vault-scanned data, but chapter/page content, TTS, and audiobook playback are not yet backed by real parsing — see "Relationship to the Phase D KMP docs" below.
- **App icon is a placeholder.** Upscaled from the Android launcher icon (192×192 → 1024×1024) to satisfy Apple's dimension requirement; replace with a real high-resolution source before any public release.
- **`ios-app-build.yml`'s unit test step uses `continue-on-error: true`** — a failing test won't fail the PR check yet. Tighten this once there's a larger, more stable test suite.

## Relationship to the Phase D KMP docs

Several other docs in this repo (`PHASES-SUMMARY.md`, `phase-d-kmp-swift-integration.md`, `PHASE-D-IMPLEMENTATION-GUIDE.md`, `v3-ios-ci-setup.md`) describe a **different, more ambitious architecture**: building Kotlin Multiplatform code into real `.xcframework` bundles (`LibravaultDomain.xcframework`, etc.) and linking them into the iOS app via SwiftPM binary targets. That work:

- Was planned in detail but **never started** — no `build-xcframeworks.gradle.kts` exists anywhere in the repo despite being referenced by name in three of those docs
- Is unrelated to how TestFlight distribution actually got solved — this doc's pipeline uses a plain Xcode App target with hand-written Swift, no KMP frameworks involved at all
- May still be a real future goal, but should be scoped and estimated fresh against the current codebase rather than trusting those docs' effort estimates, which were written before this session's discovery that even the basic "does an app target exist" question was unresolved. Real content parsing (EPUB/PDF/audio) is being built natively in Swift instead — see the "Remove mockup books from the iOS app" plan — so this KMP path is not currently on the critical path for that work.

Those four docs have been marked with a status-correction banner pointing here rather than deleted, since the Phase D KMP-linking idea itself may still be worth pursuing later.
