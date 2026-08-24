# PRD: Vault Content Source Unification

**Status:** Draft · **Author:** principal-dev-lead session, 2026-08-24 · **Base commit:** `82ba777` (dev)
**Revisits the architecture decision in:** [PR #166](https://github.com/LibraVault/reader/pull/166) (Phase 5b — original vault read/play, 2026-08-15)
**Blocks:** [#489](https://github.com/LibraVault/reader/issues/489) (vault Read Aloud), [#493](https://github.com/LibraVault/reader/issues/493) (vault audiobook background playback) — both paused pending this doc

## 1. Problem

A file stored in an Encrypted Vault should be transparent to the user: the
same reading and listening experience as any other library item, indicated
only by a padlock badge on its Library card. That is not what exists today,
on either end:

- **Browsing:** there is no padlock/lock badge on `LibraryItemCard` at all.
  Vault items are distinguished only by which vault-name filter chip they
  fall under ("Books", "Telegram", ...) — a folder-style filter, not a
  security indicator.
- **Opening:** `LibravaultNavHost` routes vault items to entirely separate
  navigation destinations — `VaultReaderScreen`/`VaultPlayerScreen` — not
  `ReaderScreen`/`PlayerScreen` with a different data source. Every screen,
  ViewModel, and UI component involved is a second implementation, not a
  parameterization of the first.

**Confirmed direction (2026-08-24):** only the *entry point* — resolving a
`LibraryItem` to bytes, vault or plaintext — should ever fork. Reading and
playback logic downstream of that resolution stays one implementation. §3's
`ContentSource` abstraction is how that gets enforced structurally, not just
stated as intent.

That fork was a deliberate, reasonable call at the time (§2), but the
predictable cost has arrived: every reader/player feature now has to be
built twice, and vault keeps landing second and incomplete. Measured, not
asserted — the pattern repeats across every feature category shipped since:

| Feature | Plain reader/player | Vault equivalent |
|---|---|---|
| Reading settings (theme/font/etc.) | `ReaderSettingsSheet` | `VaultReaderSettingsSheet` — separate file, drifted out of sync at least once (#428, theme choice not persisting) |
| Bookmarks | `BookmarksSheet` | `VaultBookmarksSheet` — separate file |
| Settings-button visible label (#424 usability fix) | `ReaderTopBar` | `VaultReaderTopBar` — needed its own patch, tracked as a known-parallel surface in its own test file's doc comment |
| Read Aloud (#137/#276) | `ReaderTopBar` + `ReaderViewModel` TTS wiring | **Missing entirely** — #489 |
| Background audio / lock-screen controls | `feature:player`'s `PlaybackService`/`MediaSession` | **Missing entirely** — `VaultPlayerScreen` is a screen-scoped, foreground-only `ExoPlayer` — #493 |

Markdown format support itself shipped ~5 weeks late to the vault relative
to the plain reader for the same structural reason (#442 — the vault
format-dispatch `when` block simply never had a `MARKDOWN` branch until
closed 2026-08-22).

## 2. Why it was forked in the first place (still a real constraint)

`LibraryItem.filePath: String` is treated as a genuinely resolvable `Uri` at
load-bearing call sites throughout the production reader/player, e.g.:

- `PdfReaderScreen` — `context.contentResolver.openFileDescriptor(fileUri, "r")`
- `PlayerViewModel` — `MediaItem.fromUri(uri)`
- `ReaderScreen`'s format dispatch, `ChapterExtractor`, `LibravaultMediaCallback`

Vault content has no real `Uri`. It is decrypted bytes served on demand
through `VaultFileReader`/`VaultSessionManager`, deliberately never written
to plaintext disk — that is a real security property of the vault (see
`docs/threat-model.md`), not incidental friction. PR #166 weighed retrofitting
a synthetic `vault://` scheme into every one of those call sites — code every
user depends on, vault or not — against a contained parallel path, under
Phase-5b time pressure, and picked the lower-blast-radius option. That
reasoning still holds for *why it happened*; it does not mean the fork
should be permanent.

## 3. Proposal — a `ContentSource` abstraction

Replace the raw `Uri`/`filePath: String` assumption at those call sites with
an interface with two implementations:

```kotlin
sealed interface ContentSource {
    data class RealFile(val uri: Uri) : ContentSource
    data class VaultEntry(
        val reader: VaultFileReader,
        val fileIdHex: String,
        val format: MediaFormat,
    ) : ContentSource
}
```

(Illustrative — exact shape is implementation work, not this doc's job to
finalize.) `ReaderScreen`, `PdfReaderScreen`, `PlayerViewModel`,
`ChapterExtractor`, and `LibravaultMediaCallback` take a `ContentSource`
instead of assuming a real `Uri`. `VaultReaderScreen`/`VaultPlayerScreen`
are deleted once their callers migrate — the Library screen resolves which
`ContentSource` an item needs and hands it to the *same* `ReaderScreen`/
`PlayerScreen` destination either way.

For EPUB/PDF/Markdown reading this is close to free: the vault-native
adapters (`VaultReadiumProvider`, `VaultProxyFdHost`/`VaultMemfdFallback`,
raw byte reads for Markdown) already do the decrypt-to-content work
`VaultReaderScreen` uses today. The remaining work is routing that through
`ReaderScreen`/`EpubReaderScreen`/`PdfReaderScreen`/`MarkdownReaderScreen`
via `ContentSource` instead of a second screen.

For audio it is the harder half: `PlaybackStateHolder`/`MediaSession`
(`LibravaultMediaCallback`) are keyed off a real Room `itemId`, which vault
content doesn't have. Two directions worth spiking before committing:

- **(a) Synthetic/ephemeral itemId** for vault items, registered
  transiently (never persisted to `Room`) so the existing `PlaybackService`/
  `MediaSession` plumbing works against it unmodified. More unifying;
  more research needed on lifecycle (what happens to a synthetic id when
  the vault session locks mid-playback).
  Recommended direction to spike first.
- **(b) A lighter parallel `MediaSession`** that still drives the *same*
  `ExoPlayer` singleton (rather than #493's originally-proposed second
  `VaultPlaybackService` running its own instance) — less unifying, lower
  risk, faster.

## 4. Migration order

Phased, not a single cutover — Phase 1 is materially lower-risk than Phase 2
and should not wait on Phase 2's design being finalized:

1. **Reading (EPUB/PDF/Markdown).** Introduce `ContentSource`, migrate the
   read-only reader call sites, delete `VaultReaderScreen`. No `MediaSession`
   involvement, no background-playback complexity. This is what #489 (Read
   Aloud) should be built against — once, on the unified `ReaderScreen`,
   rather than a second time on `VaultReaderScreen` as originally scoped.
2. **Audio.** Resolve the itemId question (§3), migrate `PlayerViewModel`/
   `LibravaultMediaCallback`, delete `VaultPlayerScreen`. #493 is this
   phase, not a standalone parallel service — re-scope it here once Phase 1
   validates the `ContentSource` pattern works end-to-end.
3. **Library UI.** Add the padlock badge to `LibraryItemCard`, gated by the
   Library-visibility setting resolved in §8. Whether vault-name chips
   survive as a secondary filter is still open (§7).

## 5. Explicitly out of scope for this migration

- Changing the vault's encryption-at-rest guarantees. `ContentSource.VaultEntry`
  carries the same constraint `VaultFileReader` already enforces today —
  decrypted bytes never touch a plaintext disk cache. Any new shared call
  site touching vault bytes needs the same audit PR #166 already did once
  for `CoverArtCache` (which defaulted to writing a plaintext cover to disk
  as a side effect — caught and fixed before it could leak a vault cover).
- Redesigning vault reading-settings/bookmarks UI beyond what unification
  requires — those already exist (`VaultReaderSettingsSheet`,
  `VaultBookmarksSheet`); folding them into the shared components is Phase 1
  scope, not a redesign.

## 6. Risk

This touches code every user depends on daily, not just vault users —
`PdfReaderScreen`, `PlayerViewModel`, `LibravaultMediaCallback` have zero
vault awareness today and need to gain it without regressing the common
case. Phase 2 (audio) is the higher-risk half: `MediaSession`/lock-screen/
Bluetooth/Android Auto integration is real hardware/OS surface that unit
tests can't fully exercise (Robolectric fakes it; the original #137 Read
Aloud work already hit real audio-focus gaps unit tests missed — see that
PR's own fix-up commits). Recommend staged rollout per phase with real
device verification, not just CI green, before either phase merges to `dev`.

## 7. Open questions (not blocking — resolve during design/implementation)

- Exact lifecycle of a synthetic vault itemId if the vault locks (session
  expires, user backgrounds the app) while audio is mid-playback via
  approach (a) in §3.
- Whether vault-name filter chips survive Phase 3 as a secondary filter, or
  get replaced entirely by the padlock-only model this doc is named for.
- Whether Phase 1 alone (reading unification) is worth shipping even if
  Phase 2 (audio) turns out to need significantly more design work than
  scoped here — current lean is yes, since it unblocks #489 independently.

## 8. User-configurable vault settings (resolved 2026-08-24)

Three questions raised while scoping #493 (vault audiobook background
playback) turned out to be product decisions this doc should own, not ones
#493 should answer standalone. Resolved as: **all three are user-configurable
settings**, not a single hardcoded behavior — the underlying privacy/security
posture varies too much by user (some want zero vault trace outside an
unlocked session; others just want folder-style organization) to pick one
default and call it done.

| Setting | Choices | Default |
|---|---|---|
| **Library visibility** | Vault items never appear in the main Library (must enter via the "Encrypted Vaults" gateway) **vs.** vault items appear inline in the Library, marked only by the Phase 3 padlock badge | Gateway-only (most private) |
| **Lock-screen / notification metadata** | Real title/author on the now-playing card and notification **vs.** generic "Vault" placeholder | Generic placeholder |
| **Background-playback auto-lock behavior** | No exemption — today's existing behavior (`VaultSessionManager.lockAll()` on `ProcessLifecycleOwner.onStop`, immediate, no timer) applies even during playback **vs.** a grace period / stay-unlocked-while-playing exemption | No exemption (today's behavior, unchanged) |
| **Stop playback on lock** | Audio stops the moment the vault locks **vs.** audio keeps playing after lock (lower-severity than leaving visual reader content exposed, since it's audio-only) | Stops on lock |

Every default above is the more secure/private option — each setting is an
opt-in loosening, not an opt-out of privacy. This matches the only existing
precedent for a vault security toggle, `VaultScreenSecurityPreference`
(`docs/threat-model.md`, `FLAG_SECURE` while vault content is on screen):
user-togglable, default on/secure.

**Where these live:** `SettingsScreen.kt`'s existing "Encrypted Vaults"
section (`SettingsScreen.kt:308-338`) already hosts one such toggle
("Screen Security") separately from the plain "Vaults" (SAF-folder) section
above it — same reasoning applies here, these are Encrypted-Vault-specific,
not general library settings. All four new settings belong in that same
section, not scattered across Reading/Playback/Privacy. No new top-level
Settings section needed; this one already exists and is the right home.

**Note on the auto-lock timer specifically:** there is no timer today —
`VaultSessionManager` locks every open vault immediately and unconditionally
on the app leaving the foreground (§ doc comment in
`VaultSessionManager.kt:47-52`). "Make the auto-lock timer configurable"
therefore means introducing a timer/grace-period concept that doesn't exist
yet, gated behind this setting, not exposing a value that's currently
hardcoded to a non-zero number.

**Implementation note, not a new phase:** these four settings are additive
to Phase 2/3 (§4), not a new phase — Phase 3 wires Library visibility +
padlock badge together; Phase 2 wires the auto-lock/stop-on-lock pair
alongside whichever itemId approach (§3) it lands on, since both need
`VaultSessionManager` and `PlaybackStateHolder`/`MediaSession` lifecycle
touching regardless. Lock-screen metadata is a small, independent
`MediaMetadata` build-time check and can land with either.
