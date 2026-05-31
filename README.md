# LibraVault

> Your library, under lock and key.

A modern, privacy-first Android app for reading EPUBs, viewing PDFs, and listening to audiobooks — with zero broad file permissions, zero required accounts and zero ads.

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Android](https://img.shields.io/badge/Android-12%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple.svg)](https://kotlinlang.org)

---

## Why LibraVault?

| Problem | LibraVault's answer |
|---|---|
| Apps demand full storage access | Scoped Storage only — you choose exactly which folders we see |
| No single app handles books *and* audio | EPUB · PDF · MP3 · M4B · OGG · FLAC · Opus · AAC |
| Closed-source privacy claims | 100% FOSS (GPL-3.0) — verify every claim yourself |
| Forced accounts and cloud sync | Fully offline, no account, no telemetry |
| Audible/Spotify UX friction | Clean home screen, persistent progress, no algorithmic clutter |

---

## Features

### Reader
- EPUB rendering via Readium Kotlin Toolkit (full EPUB 3 support)
- PDF viewer via native AndroidX PdfRenderer (API 31+)
- Dark, light, and sepia themes
- Adjustable font size (0.8×–2.0×), font family, and line spacing
- Paginated or continuous scroll mode
- Tap-zone navigation — left/right thirds of screen to turn pages
- Bookmarks and highlights for both EPUB and PDF
- Silent last-position restore — no dialogs

### Player
- MP3, M4B, OGG Vorbis, FLAC, Opus, AAC
- Chapter navigation — M4B native chapters + ID3v2 CHAP tags in MP3
- Variable playback speed 0.5×–3.0×
- Sleep timer with 6 presets, end-of-chapter option, and 10-second fade-out
- Background playback with lock screen and notification controls
- Android Auto ready via MediaSession
- Progress saved every 5 seconds

### Library
- Opens in < 200 ms — Room cache serves UI immediately
- Background scan on cold start; pull-to-refresh anytime
- Search by title, author, narrator, or series
- "Continue reading" and "Continue listening" cards
- Stale file handling — silent removal on next scan

### Privacy
- No `INTERNET` permission — ever
- No account required
- No analytics, telemetry, or remote crash reporting
- Optional local-only logging — never transmitted
- Scoped Storage — only folders you explicitly grant

---

## Tech Stack

| Layer | Library |
|---|---|
| Language & UI | Kotlin 2.0 + Jetpack Compose |
| EPUB rendering | Readium Kotlin Toolkit 3.x (BSD 3-Clause) |
| PDF rendering | AndroidX PDF Viewer + PdfRenderer (API 31+) |
| Audio playback | Media3 ExoPlayer + MediaSession |
| Storage access | Storage Access Framework (SAF) |
| Database | Room + KSP |
| DI | Hilt |
| Async | Kotlin Coroutines + Flow |
| Image loading | Coil 3 (local-only, no network fetcher) |
| Testing | JUnit 5 + MockK + Turbine + Robolectric + Compose UI Test |
| CI | GitHub Actions (free — public repo) |

---

## Module Structure

```
libravault/
├── app/                        # Entry point, navigation, manifest
├── build-logic/                # Convention plugins (shared build config)
├── core/
│   ├── database/               # Room entities, DAOs, repositories
│   ├── domain/                 # Models, interfaces, use cases (KMP-ready)
│   ├── logger/                 # Local opt-in crash logger
│   ├── storage/                # SAF vault manager, file scanner, metadata extractor
│   └── ui/                     # Material3 theme, colours, typography
└── feature/
    ├── onboarding/             # First-launch vault setup
    ├── library/                # Home screen, search, scan trigger
    ├── reader/                 # EPUB + PDF reader
    ├── player/                 # Audio player, sleep timer, chapters
    └── settings/               # App preferences, logging, cache management
```

---

## Permissions

| Permission | Purpose |
|---|---|
| `FOREGROUND_SERVICE` | Background audio playback |
| `POST_NOTIFICATIONS` | Media playback controls (Android 13+) |
| `SAF URI access` | Read user-selected vault folders only |

**Never requested:** `READ_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`, `INTERNET`, `CAMERA`, `CONTACTS`, `LOCATION`.

---

## Building

```bash
# Clone
git clone git@github.com:libravault-xyz/libravault.git
cd libravault

# Debug build
./gradlew assembleDebug

# Run JVM tests (fast — no emulator)
./gradlew testDebugUnitTest

# Release build (requires keystore.properties — see keystore.properties.template)
./gradlew assembleRelease
```

**Minimum SDK:** Android 12 (API 31)  
**Target SDK:** Android 15 (API 35)

---

## Release signing

```bash
# 1. Generate a keystore (do this once, back it up securely)
keytool -genkey -v \
  -keystore libravault-release.keystore \
  -alias libravault \
  -keyalg RSA -keysize 4096 -validity 10000

# 2. Copy the template and fill in your credentials
cp keystore.properties.template keystore.properties

# 3. Build signed release APK
./gradlew assembleRelease
```

---

## CI / CD

Three GitHub Actions workflows (all free for public repos):

| Workflow | Trigger | Duration |
|---|---|---|
| `jvm-tests.yml` | Every push | ~2–3 min |
| `ui-tests.yml` | PRs to main | ~15–20 min |
| `release.yml` | Version tags (`v*.*.*`) | ~10 min |

---

## Roadmap

| Version | Focus |
|---|---|
| **v1 (current)** | Android app — EPUB, PDF, audio, TTS, privacy-first |
| v1.1 | Clip bookmarks, home screen widget |
| v2 | DRM (Readium LCP), OPDS browsing, Comic/CBZ |
| v3 | iOS (Compose Multiplatform) |
| v4 | Desktop — Windows, macOS, Linux (KMP + SQLDelight) |

---

## Donate

LibraVault is **free, open source, and has no ads, no tracking, no accounts** — it exists because the people building it believe in it. If it brings you value, consider supporting its development.

| Currency | Address |
|----------|---------|
| **Bitcoin (BTC)** | `bc1q9y4q49lxnwrt9pnkgrxfpq92s9mvwv9espc5yg` |
| **Monero (XMR)** | `42RowRVVQgXNxC1691mAVmesXg2JR8MUNaYbnpbG7HMJ8zqExXC2qo4cYdbF9MJpE6Z8jq7ytHWhdXrtxgrFySt349R8WmF` |

Alternatively, sponsor us on [GitHub Sponsors](https://github.com/sponsors/libravault-xyz).

## Distribution

- **F-Droid** — free, full feature set, reproducible builds
- **GitHub Releases** — signed APK on every version tag
- **Obtanium - tbd
- **Google Play** — one-time purchase, tbd

---

## License

[GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.txt) — see [LICENSE](LICENSE).

*Libravault.xyz · Built with Kotlin + Jetpack Compose*

