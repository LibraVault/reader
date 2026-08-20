# Android / iOS feature parity

A running status matrix for the two native apps. Android (`app/`, `core/*`) is the
reference implementation; iOS (`ios/LibraVaultApp/`) is hand-written Swift — no shared
KMP code is actually linked into the iOS app (see
[`DomainBridge.swift`](../ios/LibraVaultApp/LibraVault/LibraVault/Sources/KmpInterop/DomainBridge.swift)'s
header), so every ✅ below on iOS is an independent, parallel implementation, not a
shared module. Update this file whenever a gap here is closed or a new one is found —
don't let it silently go stale.

| Area | Android | iOS | Notes |
|---|---|---|---|
| Folder scanning | ✅ `LibraryScannerImpl` (`core:storage`), 2-phase (stub scan, then metadata enrichment) | ✅ `LibraryFileScanner` + `AppState.loadLibrary`, same 2-phase shape | iOS phase 1 is filename-only; iOS title/author extraction from embedded metadata is still unimplemented (see next row) |
| Title/author metadata | ✅ `MetadataExtractor` (ID3/M4B tags, EPUB OPF, filename fallback) | ❌ filename-only | iOS `BookData.title`/`.author` never read embedded metadata; open gap |
| **Cover art** | ✅ `MetadataExtractor` + `CoverArtCache` (audio embedded art, EPUB OPF manifest, PDF page-1 render) | ✅ `CoverArtExtractor` + `CoverArtCache` (Swift port, same 512px cache cap) | Was [#76](https://github.com/LibraVault/reader/issues/76) — iOS previously always rendered a placeholder gradient, never real artwork. Fixed here. |
| EPUB reading | ✅ Readium 3 | ✅ `EPUBParser` (native zip/XML parsing, no Readium) | Independent implementations; both real, not mocked |
| PDF reading | ✅ `androidx.pdf` | ✅ `PDFParser` + `WidthFittingPDFView` (PDFKit) | |
| Markdown viewer | ✅ | ✅ | Phases 1–5 shipped; Mermaid rendering (phase 6) not started on either platform |
| Audio playback | ✅ Media3 `ExoPlayer` | ✅ `AudioPlaybackEngine` (`AVAudioPlayer`) | |
| Text-to-speech | ✅ system TTS + Pocket TTS (sherpa-onnx, on-device) | ✅ `AVSpeechSynthesizer` + Pocket TTS (sherpa-onnx) | |
| Bookmarks / highlights | ✅ persisted (Room) | ✅ persisted (`ReadingDataPersistence`, file-backed) | |
| Reading/listening progress | ✅ persisted | ✅ persisted | |
| Lockscreen / background audio controls | ✅ | ✅ | |
| Encrypted Vaults | ✅ full: create/unlock/list, import, read/play (EPUB/PDF/audio), cover-art thumbnails, bookmarks (EPUB+PDF)/highlights (EPUB), per-user reading settings (EPUB), Screen Security toggle | ⚠️ partial: create/unlock/list (`EncryptedVaultListView`/`CreateEncryptedVaultView`/`UnlockEncryptedVaultView`), import, Screen Security toggle (`VaultContentSecurityModifier`) on the contents list. Read/play integration (EPUB/PDF/audio) not started (#203). | No Pro tier gates it on either platform (see next row). Not yet on Android either: vault audio bookmarks (#177), a paginated non-scrolling vault PDF mode (#178). **Screen Security is intentionally weaker on iOS**: Android's `FLAG_SECURE` is a real OS-enforced block on screenshots/recording/casting; iOS has no equivalent block, only detect-and-react (blank + auto-lock on `UIScreen.isCaptured`, blank on app-switcher backgrounding) — see `docs/threat-model.md`'s "Vault content visible via screenshot/screen recording/casting" row for the full comparison. Once #203 lands, its reader/player screens will also need `.vaultContentSecurity()` applied — not yet wired since those screens don't exist yet. |
| Licensing / Pro unlock | N/A — no Pro tier | N/A — no Pro tier | The Ed25519-key/Play Billing infrastructure this row used to describe was removed; all features (incl. Encrypted Vaults) are free everywhere, donation-funded. A subscription may return once there's a real install base to justify it. |
| Donations (BTC/XMR) | ✅ BTCPay-verified flow (`SettingsScreen.kt`'s `DonateSheet`) | ❌ deliberately absent | iOS explicitly omits the donate button rather than show one that does nothing — see `SettingsView.swift`'s `supportSection` comment |
| CBZ / MOBI / DJVU | ❌ (not supported on either platform — see `KNOWN_LIMITATIONS.md`) | ❌ | Both scanners recognize `.cbz`/`.mobi` extensions for future use but don't parse them yet |

## Updating this table

When closing a parity gap: flip the relevant iOS/Android cell to ✅, note the PR that
did it in the Notes column, and remove any now-stale caveat. When finding a new gap
(during development, a bug report, or an audit), add a row rather than letting it live
only in a GitHub issue — issues get closed and forgotten; this file is the standing
reference for "does X actually work the same on both platforms."
