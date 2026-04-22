# Libravault

> Your library, under lock and key.

A modern, privacy-first Android app for reading EPUBs, viewing PDFs, and listening to audiobooks — with zero broad file permissions and zero required accounts.

## Why Libravault?

| Problem | Libravault's answer |
|---|---|
| Apps demand full storage access | Scoped Storage only — you choose exactly which folders we see |
| No single app handles books *and* audio | EPUB · PDF · MP3 · M4B in one unified library |
| Closed-source privacy claims | 100% FOSS (GPL-3.0) — verify every claim yourself |
| Forced accounts and cloud sync | Fully offline, no account, no telemetry |

## Tech Stack

- **Language:** Kotlin 2.0 + Jetpack Compose
- **Architecture:** Clean Architecture · MVVM · Multi-module Gradle
- **Storage:** Storage Access Framework (SAF) — no `MANAGE_EXTERNAL_STORAGE`
- **Database:** Room with Flow-based reactive queries
- **DI:** Hilt
- **EPUB (M2):** Readium Kotlin Toolkit
- **Audio (M3):** Media3 ExoPlayer + MediaSession

## Module Structure

```
libravault/
├── app/                    # Application entry point, navigation host
├── build-logic/            # Convention plugins (shared build config)
├── core/
│   ├── database/           # Room entities, DAOs, database
│   ├── domain/             # Models, repository interfaces, use cases
│   ├── storage/            # SAF vault manager, file scanner
│   └── ui/                 # Shared theme (colors, typography, Material3)
└── feature/
    ├── onboarding/         # First-launch vault setup flow
    ├── library/            # Home screen — continue cards, browse, search
    ├── reader/             # EPUB + PDF reader (M2)
    └── player/             # Audio player (M3)
```

## Permissions

Libravault requests the **minimum possible** permissions:

| Permission | Purpose |
|---|---|
| `FOREGROUND_SERVICE` | Background audio playback |
| `POST_NOTIFICATIONS` | Media playback controls (Android 13+) |
| SAF URI access | Read user-selected vault folders (not a manifest permission) |

**Never requested:** `READ_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`, `INTERNET`, `CAMERA`, `CONTACTS`, `LOCATION`.

## Development Milestones

| Milestone | Status | Description |
|---|---|---|
| M0 — Foundation | ✅ In progress | Gradle setup, Room, SAF, Hilt, navigation, theme |
| M1 — Library | 🔜 | File scanner, metadata extraction, home screen |
| M2 — Reader | 🔜 | EPUB (Readium) + PDF viewer, bookmarks, progress |
| M3 — Player | 🔜 | Audio playback, MediaSession, sleep timer, speed |
| M4 — Polish | 🔜 | Search, accessibility, dark/sepia modes |
| M5 — Release | 🔜 | F-Droid reproducible build, Play Store listing |

## Building

```bash
# Debug build
./gradlew assembleDebug

# Run unit tests
./gradlew testDebugUnitTest

# Check lint
./gradlew lint
```

Minimum SDK: **Android 11 (API 30)**  
Target SDK: **Android 15 (API 35)**

## Distribution

- **F-Droid** — free, full feature set, reproducible builds
- **Google Play** — one-time purchase (~€3.99)
- **GitHub Releases** — signed APK

## License

GPL-3.0 — see [LICENSE](LICENSE).

---

*Libravault.xyz · Built with Kotlin + Jetpack Compose*
