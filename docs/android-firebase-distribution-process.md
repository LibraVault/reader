# Android Firebase App Distribution Process

**Status: Bootstrapped and verified 2026-08-06 — first CI run succeeded end to end.** This is the ground-truth doc for Android beta distribution, replacing the ad hoc, ClickOps-only instructions that used to live at the repo root (`APP_DISTRIBUTION_SETUP.md`, `FIREBASE_TESTING_SETUP.md`, `FIREBASE_TESTING_CHECKLIST.md`) — those described a maintainer manually building an APK locally and dragging it into the Firebase Console. This doc describes the CI-automated replacement, structured the same way as [iOS-TESTFLIGHT-RELEASE-PROCESS.md](iOS-TESTFLIGHT-RELEASE-PROCESS.md).

## What this is

A `workflow_dispatch`-only GitHub Actions pipeline (`.github/workflows/android-firebase-distribution.yml`) that builds a signed `play`-flavor release APK and uploads it to Firebase App Distribution via the `firebase-tools` CLI. Same philosophy as TestFlight: a beta build is a deliberate human action, not something that fires on every push.

**No Firebase SDK, Gradle plugin, or `google-services.json` is added to the app.** Distribution happens entirely in CI, acting on the already-built APK from the outside — nothing ships inside the binary. This matters because the project's stated privacy posture explicitly denylists analytics/tracking SDKs (see `docs/v3-ios-ci-setup.md`'s dependency-denylist section) and F-Droid's `fdroid` flavor must stay zero-network. The `play` flavor already carries network-capable dependencies (`playImplementation` OkHttp/Retrofit for Play Billing), so building it for App Distribution changes nothing about the app's dependency graph.

## Triggering a distribution build

```bash
gh workflow run android-firebase-distribution.yml --repo LibraVault/reader --ref <branch> \
  -f release_notes="What changed in this build" \
  -f groups="testers"
```

or via GitHub's UI: Actions → "Android Firebase Distribution" → Run workflow. Both inputs are optional (defaults: generic release notes, `groups=testers`).

## Required GitHub secrets

| Secret | What it is |
|---|---|
| `RELEASE_KEYSTORE` | Base64-encoded release keystore — **reused from `release.yml`**, same key signs both the F-Droid and Play builds |
| `KEYSTORE_PASSWORD` | Reused from `release.yml` |
| `KEY_ALIAS` | Reused from `release.yml` |
| `KEY_PASSWORD` | Reused from `release.yml` |
| `FIREBASE_APP_ID` | Firebase Console → Project Settings → General → "Your apps" → Android app (`xyz.libravault.app`) → App ID, format `1:XXXXXXXXXX:android:XXXXXXXXXXXXXXXX` |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | Base64 of a service-account key scoped to `roles/firebaseappdistro.admin` — see bootstrap below |

Verify what's currently set (values are never readable, only names):
```bash
gh secret list --repo LibraVault/reader
```

## One-time bootstrap (manual — requires GCP/Firebase Console access)

1. Confirm the Firebase project and Android app for `xyz.libravault.app` exist. As of 2026-08-06 the app lives in the **`libravault-xyz`** Firebase project (App ID `1:170483129446:android:984845c0fa2f630e26e1a7`) — registered directly via the Firebase Management API, since neither pre-existing project actually had a correctly-named app (`libravault-testing` had one Android app but registered under the wrong package, `libravault.xyz`; `libravault-xyz` had none). Copy the App ID into the `FIREBASE_APP_ID` secret.
2. Create a CI-scoped service account and grant App Distribution admin rights:
   ```bash
   gcloud iam service-accounts create firebase-app-distro-ci --project=PROJECT_ID

   gcloud projects add-iam-policy-binding PROJECT_ID \
     --member=serviceAccount:firebase-app-distro-ci@PROJECT_ID.iam.gserviceaccount.com \
     --role=roles/firebaseappdistro.admin

   gcloud iam service-accounts keys create firebase-key.json \
     --iam-account=firebase-app-distro-ci@PROJECT_ID.iam.gserviceaccount.com
   ```
3. `base64 -w0 firebase-key.json` and paste the result into the `FIREBASE_SERVICE_ACCOUNT_JSON` GitHub secret, then delete the local `firebase-key.json`.
4. In Firebase Console → App Distribution → Testers and Groups, create (or confirm) a group named `testers` and add testers to it. Pass a different `groups` value at dispatch time to target a different group.

## How the pipeline works (`android-firebase-distribution.yml`)

1. **Checkout**, JDK 17, Gradle setup.
2. **Set up signing** — identical to `release.yml`: decode `RELEASE_KEYSTORE`, write `keystore.properties` from the three password/alias secrets.
3. **Build** — `./gradlew assemblePlayRelease`.
4. **Set up Firebase credentials** — decode `FIREBASE_SERVICE_ACCOUNT_JSON` to `firebase-key.json`.
5. **Distribute** — locate the APK (its filename is dynamic, e.g. `libravault-<branch>-play-release.apk`, per the branch-aware naming in `app/build.gradle.kts`), then run:
   ```bash
   npx --yes firebase-tools@13.29.1 appdistribution:distribute "$APK_PATH" \
     --app "$FIREBASE_APP_ID" \
     --groups "$TESTER_GROUPS" \
     --release-notes "$RELEASE_NOTES"
   ```
   Authentication comes from `GOOGLE_APPLICATION_CREDENTIALS` pointing at the decoded `firebase-key.json` — `firebase-tools` has no `--credential-file` flag (an early version of this workflow assumed one and failed at runtime; see gotchas below).
6. Upload the APK as a build artifact (30-day retention).
7. **Clean up** (`if: always()`) — remove the keystore, `keystore.properties`, and `firebase-key.json`. Never leave key material on the runner.

## Gotchas

- **The `firebase-tools` version is pinned** (`npx firebase-tools@13.29.1`) rather than using `@latest`. Bump it deliberately when needed — an unpinned CLI version drifting under you is exactly the kind of surprise that cost real debugging time on the iOS side (see the Xcode-version gotcha in the TestFlight doc).
- **APK output filename is dynamic**, not a fixed `app-release.apk` — `app/build.gradle.kts` embeds the git branch in the filename for local-build disambiguation. The workflow uses `find ... -name "*.apk" | head -1` rather than a hardcoded path.
- **Don't add a Firebase Gradle plugin or `google-services.json`.** If a future change wants Crashlytics/Analytics, that's a deliberate scope decision that conflicts with this project's privacy stance and needs to be discussed first — this pipeline intentionally stays CLI-only, CI-side, with zero app-side footprint.

## Post-upload: testers

Firebase emails testers in the target group automatically once the upload completes — unlike TestFlight, there's no separate manual "invite testers" step required after each distribution, only the one-time group setup in the bootstrap section above.

## Known issues: tablet install fails with "open on your mobile device"

**Reported 2026-08-16** (tester M.): opening the invite link on an Android **tablet** can land on `appdistribution.firebase.google.com/mobilerequired` with the message "Open the invitation email on your mobile device to install test apps" — even though the tablet is a valid Android install target. See [#215](https://github.com/LibraVault/reader/issues/215).

This is a Firebase-side limitation, not a LibraVault bug: the App Distribution web flow gates install eligibility on the browser's User-Agent string rather than the actual OS, and some Android tablets — especially in split-screen/desktop-site browser modes (e.g. Samsung DeX-style layouts) — send a User-Agent Firebase doesn't classify as "mobile." We don't control that detection logic.

If a tester hits this, have them try (in order):

1. In Chrome, menu (⋮) → uncheck **"Desktop site"**, then reopen the invite link.
2. Open the invitation email directly in the **Gmail app** (not a browser) and tap the link from there.
3. Install the **Firebase App Tester** app from Play Store — it handles invite links more reliably than the browser flow.
4. As a last resort, open the invite link on a phone instead of a tablet.
