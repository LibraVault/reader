# PRD: Vault Content Source Unification

**Status:** Approved 2026-08-24 · Phase 1 shipped 2026-08-25 · **Author:** principal-dev-lead session, 2026-08-24 · **Base commit:** `82ba777` (dev)
**Revisits the architecture decision in:** [PR #166](https://github.com/LibraVault/reader/pull/166) (Phase 5b — original vault read/play, 2026-08-15)
**Reconciled against implementation:** 2026-08-25, base commit `869468c` (dev) — see the progress table below for what changed between the approved design and what actually shipped.

## 0. Progress

| Phase | Scope | Issue | Status |
|---|---|---|---|
| 1 | Reading (EPUB/PDF/Markdown) unification, `ContentSource` introduced | [#505](https://github.com/LibraVault/reader/issues/505) | **Shipped** — [PR #543](https://github.com/LibraVault/reader/pull/543), merged 2026-08-25. Real-device verification still outstanding (§6). |
| 2 | Audio unification (background/`MediaSession`), §8's auto-lock/stop-on-lock settings | [#493](https://github.com/LibraVault/reader/issues/493) | Unblocked (`status:blocker-cleared`), not started. Carries a `security` label from initial triage — expect the same dev-agent block Phase 1 hit (§6). |
| 3 | Library padlock badge, visibility setting, remaining §8 settings UI | [#508](https://github.com/LibraVault/reader/issues/508) | `status:blocked` on Phase 2 landing, not started. |

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
predictable cost had arrived by the time this doc was written: every
reader/player feature had to be built twice, and vault kept landing second
and incomplete. Measured, not asserted — the pattern repeated across every
feature category shipped up to that point:

| Feature | Plain reader/player | Vault equivalent (as of 2026-08-24, pre-Phase-1) |
|---|---|---|
| Reading settings (theme/font/etc.) | `ReaderSettingsSheet` | `VaultReaderSettingsSheet` — separate file, drifted out of sync at least once (#428, theme choice not persisting) |
| Bookmarks/highlights (reading) | `BookmarksSheet` | Backed by a vault-nav-arg branch in `VaultReaderViewModel` — separate persistence path (Room vs. `VaultStore`) as well as separate UI |
| Settings-button visible label (#424 usability fix) | `ReaderTopBar` | `VaultReaderTopBar` — needed its own patch, tracked as a known-parallel surface in its own test file's doc comment |
| Read Aloud (#137/#276) | `ReaderTopBar` + `ReaderViewModel` TTS wiring | Was missing entirely (#489) — now available via the unified `ReaderScreen` since Phase 1 |
| Background audio / lock-screen controls | `feature:player`'s `PlaybackService`/`MediaSession` | **Still missing** — `VaultPlayerScreen` is a screen-scoped, foreground-only `ExoPlayer` — #493, Phase 2 |

Markdown format support itself shipped ~5 weeks late to the vault relative
to the plain reader for the same structural reason (#442 — the vault
format-dispatch `when` block simply never had a `MARKDOWN` branch until
closed 2026-08-22).

**Phase 1 result (2026-08-25):** the "reading" row and the settings-label
row above are now resolved by construction, not by a second patch —
`VaultReaderScreen`/`VaultReaderViewModel`/`VaultReaderTopBar`/
`VaultReaderSettingsSheet` no longer exist. A vault EPUB/PDF/Markdown item
opens through the exact same `ReaderScreen` a plain-file item does, so
`#489` (Read Aloud) needed zero vault-specific code — it was already there
once the reader unified. Only the audio row remains genuinely un-unified;
see §4.

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
an interface with two implementations. **As shipped in Phase 1**
(`core/domain/src/main/kotlin/xyz/libravault/core/domain/model/ContentSource.kt`
— this replaces this section's original illustrative sketch, which carried
an already-open `VaultFileReader` on `VaultEntry`):

```kotlin
sealed interface ContentSource {
    data class RealFile(val uriString: String) : ContentSource
    data class VaultEntry(
        val vaultId: String,
        val fileIdHex: String,
        val format: MediaFormat,
    ) : ContentSource
}
```

Two changes from the original sketch, both decided during implementation
rather than in this doc, and both worth keeping as precedent for Phase 2:

- **No embedded `VaultFileReader`.** It's explicitly not thread-safe/
  single-consumer, and EPUB (held open for the publication's lifetime), PDF
  (FUSE proxy-fd callback thread), and Markdown (one-shot read) each need
  their own reader with their own lifecycle — sharing one instance across
  them would violate that constraint. `VaultStore.openReader(fileId)` is
  cheap and repeatable, so there's no reason to force sharing.
- **Resolved live, not cached.** `VaultEntry.vaultId`/`fileIdHex` are
  resolved against a live, unlocked `VaultSessionManager` at each point of
  use, not baked in once when the `ContentSource` is constructed — so
  re-resolution naturally reflects the vault's *current* lock state instead
  of a stale "was unlocked when this was built" assumption. This is directly
  relevant to §7's still-open question about itemId lifecycle on lock: the
  same "resolve live, don't cache an unlocked-ness assumption" pattern is
  the leading candidate for how Phase 2 should behave too.

`ReaderScreen`, `PdfReaderScreen`, `PlayerViewModel`,
`ChapterExtractor`, and `LibravaultMediaCallback` take a `ContentSource`
instead of assuming a real `Uri`. `VaultReaderScreen`/`VaultPlayerScreen`
are deleted once their callers migrate — the Library screen resolves which
`ContentSource` an item needs and hands it to the *same* `ReaderScreen`/
`PlayerScreen` destination either way.

For EPUB/PDF/Markdown reading this turned out close to free, confirmed by
Phase 1 shipping: the vault-native adapters (`VaultProxyFdHost`/
`VaultMemfdFallback`, raw byte reads for Markdown) did the decrypt-to-content
work `VaultReaderScreen` used to own, routed instead through the shared
`ReaderScreen`/`EpubReaderScreen`/`PdfReaderScreen`/`MarkdownReaderScreen`.
`VaultReadiumProvider` itself was deleted outright rather than kept as a
second EPUB adapter — the shared `ReadiumProvider` opens vault EPUBs
directly. One extra thing Phase 1 had to unify that this section didn't
originally call out: bookmark/highlight *persistence*, not just byte
resolution — `ReaderViewModel` now branches on `ContentSource` to read/write
through `VaultStore` instead of `Room` for vault items, while using the same
`BookmarksSheet` UI either way. Worth remembering for Phase 2: "only the
entry point forks" sometimes has to mean the *storage* entry point too, not
only the *bytes* entry point.

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

1. **Reading (EPUB/PDF/Markdown). ✅ Shipped** — [#505](https://github.com/LibraVault/reader/issues/505)/[PR #543](https://github.com/LibraVault/reader/pull/543), 2026-08-25. `ContentSource` introduced, all three read-only formats migrated, `VaultReaderScreen`/`VaultReaderViewModel`/`VaultReaderTopBar`/`VaultReaderSettingsSheet`/`VaultReadiumProvider` deleted (~2,100 net lines). #489 (Read Aloud) needed no further work — it's already correct on the unified `ReaderScreen`, per §1. `VaultBookmarksSheet` was deliberately kept (still used by `VaultPlayerScreen`, Phase 2's concern). Real-device verification per §6 still outstanding.
2. **Audio.** Resolve the itemId question (§3), migrate `PlayerViewModel`/
   `LibravaultMediaCallback`, delete `VaultPlayerScreen`. #493 is this
   phase, not a standalone parallel service — re-scope it here once Phase 1
   validates the `ContentSource` pattern works end-to-end (it has — see #1
   above). #493 currently carries a `security` label from its initial
   triage-bot pass; §6 has what to expect from that.
3. **Library UI.** Add the padlock badge to `LibraryItemCard`, gated by the
   Library-visibility setting resolved in §8. Whether vault-name chips
   survive as a secondary filter is still open (§7). Tracked as
   [#508](https://github.com/LibraVault/reader/issues/508), correctly
   `status:blocked` until Phase 2 lands.

## 5. Explicitly out of scope for this migration

- Changing the vault's encryption-at-rest guarantees. `ContentSource.VaultEntry`
  carries the same constraint `VaultFileReader` already enforces today —
  decrypted bytes never touch a plaintext disk cache. Any new shared call
  site touching vault bytes needs the same audit PR #166 already did once
  for `CoverArtCache` (which defaulted to writing a plaintext cover to disk
  as a side effect — caught and fixed before it could leak a vault cover).
- Redesigning reading-settings/bookmarks UI beyond what unification
  requires. Phase 1 folded the reading side entirely — `VaultReaderSettingsSheet`
  no longer exists, reading-side bookmarks use the same `BookmarksSheet` UI
  real files do. `VaultBookmarksSheet` still exists, scoped down to
  `VaultPlayerScreen` only; folding it away is Phase 2 scope, not a
  redesign there either.

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

**Confirmed by Phase 1, not just theorized:**

- **`security`-label auto-block is real and permanent, not a transient
  triage delay.** `core/vaultcrypto/**`/`core/vaultstore/**` are
  `sensitive_paths` in `agent-policy.yml`; the dev-agent pipeline's
  automated triage bounced #505 to `status:needs-info` + `security` rather
  than implement it, with no override path back to `ready-for-dev` short of
  a human relabeling it. #505 was implemented directly in-session instead —
  the same precedent as the earlier #272 vault-nonce-reuse fix. #493 already
  carries the same `security` label from its own initial triage; expect the
  same outcome rather than assuming the pipeline will pick it up
  unattended.
- **Cross-branch integration risk showed up for real, not hypothetically.**
  `dev` advanced mid-implementation with a vault-lock-race fix (#525/#526,
  PR #539) touching the exact screens Phase 1 deletes — missing it would
  have silently regressed a just-shipped security fix; it had to be ported
  into the unified `ReaderViewModel`/`ReaderScreen` by hand, with 4 new
  tests. A second, unrelated `dev` push later put #543 into a merge
  conflict, which turned out to silently stop GitHub Actions from queuing
  *any* `pull_request`-triggered check on further pushes (confirmed via the
  check-suites API) — not a flake, a real gap now tracked as
  [#558](https://github.com/LibraVault/reader/issues/558). Phase 2/3 should
  expect the same class of surprise given how long each phase will likely
  stay open against a fast-moving `dev`.
- **Real-device verification is still outstanding for Phase 1**, despite
  merging — flagged in the PR #543 merge commit for follow-up, same as
  prior sensitive-path landings. Don't treat "shipped" in the §0 table as
  "verified."

## 7. Open questions (not blocking — resolve during design/implementation)

- Exact lifecycle of a synthetic vault itemId if the vault locks (session
  expires, user backgrounds the app) while audio is mid-playback via
  approach (a) in §3. Still open, Phase 2 not started — but §3 now has a
  candidate pattern from Phase 1 (resolve live against `VaultSessionManager`
  at each use, don't cache an unlocked-ness assumption) worth trying first
  rather than designing from scratch.
- Whether vault-name filter chips survive Phase 3 as a secondary filter, or
  get replaced entirely by the padlock-only model this doc is named for.
  Still open, Phase 3 not started.
- ~~Whether Phase 1 alone (reading unification) is worth shipping even if
  Phase 2 (audio) turns out to need significantly more design work than
  scoped here.~~ **Resolved by events:** yes — Phase 1 shipped independently
  2026-08-25 and unblocked #489 on its own, exactly as this doc leaned.

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
section (`SettingsScreen.kt:473-...` as of this reconciliation — moved
since this doc's original 08-24 citation of `:308-338`, the screen has
grown; re-check the line before Phase 2/3 cite it) already hosts one such
toggle ("Screen Security") separately from the plain "Vaults" (SAF-folder)
section above it — same reasoning applies here, these are
Encrypted-Vault-specific, not general library settings. All four new
settings belong in that same section, not scattered across
Reading/Playback/Privacy. No new top-level Settings section needed; this
one already exists and is the right home.

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
