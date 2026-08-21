# Known Limitations

LibraVault is intentionally a focused, privacy-first reader. The items
below are deliberate non-features, not bugs. Each is documented here so
contributors don't mistake them for gaps, and so users don't ask
"why doesn't it…?" before reading.

## Networking

- **No cloud sync.** Bookmarks, highlights, and reading progress stay
  on-device. Backup/restore via Android's device-transfer mechanism is
  supported (see `app/src/main/res/xml/data_extraction_rules.xml`) but
  not via Drive / iCloud / a LibraVault server.
- **F-Droid flavor has zero outbound network calls.** The `INTERNET`
  permission is stripped from `app/src/fdroid/AndroidManifest.xml`.
  Donations are BTC/XMR addresses only, copy-pasted by the user.

## DRM / Purchased content

- **No DRM.** No LCP (Readium's licensed content protection), no
  Adobe Digital Editions, no Apple FairPlay. You can only read files
  you own or have explicit permission to read.
- **No OPDS catalog browsing.** You must place files in a folder the
  app can access via SAF (Storage Access Framework). No
  library-borrowing integration with public libraries.

## Formats

- **EPUB 2 and EPUB 3** — full support via Readium 3.
- **PDF** — via `androidx.pdf` (alpha quality on older devices).
- **Audio** — MP3, M4B, OGG, FLAC, OPUS, AAC. No DRM-protected
  audiobook formats (AAX, etc.).
- **No CBZ / CBR comics.** Planned for v2.
- **No DJVU, no MOBI.** Use Calibre to convert.

## Library size

- Designed for personal libraries of a few thousand items.
  `LibraryScannerImpl.enrichMetadata` Phase 2 processes files
  sequentially on a single coroutine; 10 k+ items will take several
  minutes on first run.
- The cover art cache caps each cover at 512 px on the long edge, on
  both Android (`core:storage`'s `CoverArtCache`) and iOS (its Swift
  port of the same). No folder-level cover art for series; each book
  gets its own. See `docs/ios-android-feature-parity.md` for the full
  platform status matrix.

## Editing

- **No built-in annotation editor.** Highlights are
  tap-to-create / tap-to-delete. Multi-line or styled annotations are
  not supported.

## Sync & sharing

- **No shared library between devices.** A user with two phones has
  two independent libraries (unless they manually copy files via
  cloud storage and re-grant SAF access).
- **No export of highlights to Markdown / Notion / Readwise.** v2
  candidate.

## Donations

- The Play flavor's donation flow uses BTCPay (self-hosted). It's
  not a one-tap "Buy Pro" — it's a manual crypto transfer.
- There is no Pro tier at all right now, on any flavor. No unlock, no
  in-app subscription, no per-book purchase — every feature, including
  Encrypted Vaults, is free and donation-funded.
- A $1/month recurring option exists via an external Buy Me a Coffee link
  on the website's Support page (`support.html`) — the same page the in-app
  "Support the Project" button already opens on both platforms. It's a
  plain link hop, not an in-app purchase: no app code, no entitlement
  tracking, no network calls added. A real in-app/store subscription (Play
  Billing / StoreKit) is still a possible future direction once there's a
  real install base to justify it, not before.

## Accessibility

- Full-screen reader, font scaling, theme switching, and TalkBack
  support via standard Compose semantics.
- No dyslexic-friendly font baked in (use system font / OpenDyslexic
  via the system font picker).

## Internationalisation

- UI strings are English-only as of 0.2.0. Translations welcome via
  PR; the build pulls strings from `res/values/strings.xml` and the
  `*Res` modules.

## Roadmap

- v2 candidates: LCP / DRM, OPDS browsing, CBZ / CBR comics,
  bookmark export, recovery flow for lost Pro keys.
- See `docs/v1.1-roadmap.md` for the most recent shipped-set
  records (clip bookmarks were considered and descoped).
- See `docs/threat-model.md` for the security model.