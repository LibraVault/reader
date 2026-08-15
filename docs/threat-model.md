# LibraVault threat model

This document is the engineering-side complement to `SECURITY.md`. It is
the source of truth for:

- What assets LibraVault protects (and what we explicitly don't).
- Which threat actors we design against, and which are out of scope.
- The mitigation that defends each asset, with a code reference.

Update this file whenever you add a new asset, a new outbound call,
or a new parser surface. The "Test coverage" column links to the
test file that proves the mitigation is in place.

## Asset inventory

| Asset | Storage | Test coverage |
|---|---|---|
| User library files | SAF folders (never copied) | `core/storage/src/test/.../LibraryScannerImplTest.kt` |
| Reading progress / bookmarks / highlights | Room `libravault.db` | `feature/reader/src/test/.../ReaderViewModelTest.kt` |
| Cover art cache | App cache, SHA-256 keyed | `core/storage/src/test/.../CoverArtCacheTest.kt` |
| Encrypted Vault content (imported files) | App-private dir, chunked AES-256-GCM ciphertext, opaque hex filenames | `core/vaultcrypto/src/test/.../ChunkedVaultRoundTripTest.kt`, `.../TamperDetectionTest.kt` |
| Encrypted Vault manifest (titles, authors, highlights, cover art) | Same dir as content, encrypted as one manifest file — never Room, never the plaintext cover-art cache | `core/vaultstore/src/test/.../VaultLeakClosureTest.kt`, `.../VaultStoreHasNoLeakSurfaceDependencyTest.kt` |
| Vault Master Key (VMK) | In memory only while unlocked (never persisted in the clear); wrapped two independent ways at rest — see below | `core/vaultcrypto/src/test/.../VaultKeyManagerTest.kt` |
| VMK, PIN path | Wrapped by a PIN/Argon2id-derived key, itself wrapped by a non-exportable, hardware-backed (StrongBox/TEE) Android Keystore key | `core/vaultstore/src/test/.../VaultStoreTest.kt` (via `FakeHardwareKeyWrap`) |
| VMK, recovery-key path | Wrapped by a separate 256-bit recovery key, shown to the user once at vault creation, never persisted — deliberately independent of the Keystore layer | `core/vaultcrypto/src/test/.../VaultKeyManagerTest.kt`, `feature/vault/src/test/.../RecoveryKeyFormatTest.kt` |
| Vault registry (which vaults exist, their display names) | `vaults.json`, app-private dir, **plaintext** — see "Accepted residual leaks" below | `core/vaultstore/src/test/.../VaultRegistryTest.kt` |
| Failed-unlock-attempt counter (anti-brute-force throttle state) | Per-vault config file, alongside the vault it protects | `core/vaultstore/src/test/.../UnlockAttemptThrottleTest.kt` |

## Threat matrix

The "Likelihood × Impact" is a rough 1-5 scale from the team's review
sessions. Likelihood = how often we'll encounter the trigger in
practice. Impact = what breaks if the mitigation fails.

| Threat | Trigger | L | I | Mitigation | Code |
|---|---|---|---|---|---|
| Malicious EPUB OOM via cover image | Adversarial EPUB with a multi-MB cover | 3 | 4 | `MetadataExtractor.extractEpub` caps entry size at 5 MB before reading; `CoverArtCache.calculateSampleSize` capped at 16; 0×0 header rejected pre-decode | `core/storage/.../MetadataExtractor.kt`, `core/storage/.../CoverArtCache.kt` |
| Malicious EPUB OOM via XML expansion | OPF declares a billion-laughs DTD | 2 | 4 | `FEATURE_PROCESS_DOCDECL=false` on every XmlPullParser | `core/storage/.../MetadataExtractor.kt` |
| Malicious EPUB XXE | OPF/container.xml declares external entity | 2 | 4 | Same — `FEATURE_PROCESS_DOCDECL=false` | same |
| Malicious audio hangs IO thread | Corrupt MP3 frame makes MediaMetadataRetriever spin | 3 | 3 | `withTimeout(10_000)` wraps the entire extract (not just `setDataSource`) | `core/storage/.../MetadataExtractor.kt:extractAudio` |
| Cold-launch ACTION_VIEW intent dropped silently | `LaunchedEffect(nav)` races with `onNewIntent` when nav is null | 2 | 3 | `pendingIntent` as `mutableStateOf<Intent?>`; consumer keyed on (pendingIntent, nav) | `app/.../MainActivity.kt` |
| Bookmark DB row with bad color hex wipes all highlights | User restores from backup with corrupt `colorHex` | 2 | 3 | Explicit try/catch + log for IllegalArgumentException / JSONException per highlight | `feature/reader/.../EpubReaderScreen.kt` |
| Enrichment scope never cancelled | `LibraryScannerImpl` runs forever on a 10 k-item library | 4 | 2 | `backgroundScope` is now lifecycle-bound (TODO WS3.8); cancellation hook on next scan via `enrichmentJob?.cancel()` | `core/storage/.../LibraryScannerImpl.kt` |
| Device image copied (`/data` extracted, device off/locked) | Attacker with physical access images storage, no live device | 2 | 5 | VMK is never derivable from disk alone: the PIN-wrapped path requires the non-exportable Keystore key too (nothing to brute-force offline), and the recovery-key path requires a 256-bit key that was never persisted | `core/vaultcrypto/src/test/.../VaultKeyManagerTest.kt`, `core/vaultstore/src/test/.../VaultStoreTest.kt` |
| Live device, repeated PIN guessing | Attacker holds an unlocked-screen device and tries PINs in the vault UI | 3 | 4 | Exponential backoff after 3 free attempts, capped at 5 min; deliberately no auto-wipe (an attacker could otherwise trigger destruction on purpose); counter survives process restart | `core/vaultstore/src/test/.../UnlockAttemptThrottleTest.kt` |
| Vault content/manifest tampered on disk | Attacker (or corruption) modifies ciphertext bytes | 2 | 4 | Every chunk is AEAD-authenticated with the header fields bound into the AAD, so header tampering invalidates all chunks, not just a detached signature; truncation is caught by an authenticated total-length bound + eager chunk-0 auth at open time | `core/vaultcrypto/src/test/.../TamperDetectionTest.kt`, `.../HeaderValidationTest.kt`, `.../VaultFormatVersionTest.kt` |
| Nonce reuse across chunks/files | Implementation bug reuses an AES-GCM nonce | 1 | 5 | Nonces are HMAC-SHA256-derived per chunk (deterministic from file id + chunk index), not random — reuse is structurally impossible, not just unlikely | `core/vaultcrypto/src/test/.../NonceUniquenessTest.kt` |
| Vault content visible via screenshot/screen recording/casting | User (or malware) captures the screen while a vault is unlocked | 3 | 3 | `FLAG_SECURE` while vault content is on screen — unconditional on the recovery-key display/entry steps, user-togglable (default on) elsewhere via the "Screen Security" setting | `feature/vault/.../SecureScreenEffect.kt`, `feature/vault/src/test/.../VaultScreenSecurityPreferenceTest.kt` |
| Vault stays unlocked after the user walks away | "Glance-over-the-shoulder" / left-unattended device | 3 | 4 | Every open vault re-locks (VMK zeroed and dropped) the instant the whole app leaves the foreground, via `ProcessLifecycleOwner` — not tied to any one screen | `core/vaultstore/src/test/.../VaultSessionManagerTest.kt` |
| Vault content leaks into the app's existing unencrypted surfaces | Title/author/cover art/highlights accidentally routed to Room or the plaintext cover-art cache | 2 | 5 | `VaultStore` has no code path to Room or `core.storage.CoverArtCache` — asserted by reflection, not just "we didn't call it"; separately, `MetadataExtractor.extractWithoutCaching` is the only cover-art path the vault import flow uses, and it never calls `CoverArtCache.save` | `core/vaultstore/src/test/.../VaultStoreHasNoLeakSurfaceDependencyTest.kt`, `.../VaultLeakClosureTest.kt`, `core/storage/src/test/.../MetadataExtractorTest.kt` |

## Accepted residual leaks (Encrypted Vaults)

Honest entries, not oversights — things an attacker with access to the
device's storage (but not the PIN/recovery key) can still learn about a
vault, and the reasoning for why we didn't try to hide them.

| What leaks | Why | Accepted because |
|---|---|---|
| That an Encrypted Vault exists at all, and how many | `vaults.json` lists every vault's id | The vault picker has to show *something* before unlock — there's no way to list vaults without listing them. |
| The vault's display name (e.g. "Personal", "Client Files") | Same `vaults.json`, plaintext | Same reason — and a user who wants to hide the vault's *purpose* can pick a neutral name; the app can't make that choice for them. |
| Approximate file count and size per vault | Each file is one opaque hex-named file on disk; size correlates with plaintext size plus fixed per-32 KiB-chunk AEAD tag overhead | Hiding this would mean padding every file to a fixed size, at a real storage-cost tradeoff not currently taken on. Matches the same accepted tradeoff the rest of the app already makes for file sizes/counts. |
| That the app has Encrypted Vault functionality at all | Present in every install | Not a per-user secret — it's a documented feature. |

## Support / donations

Replaced entirely by an external link — no donation code, state machine, or
network client exists in the app on any flavor or platform anymore. Tapping
"Support the Project" in Settings opens `https://libravault.xyz/support.html`
via `Intent.ACTION_VIEW` (Android, both flavors, `SUPPORT_URL` in
`feature/settings/.../SupportLink.kt`) or `Link` (iOS,
`SettingsView.supportURL`). The literal URL is pinned by a regression test on
each platform (`SupportLinkTest.kt`, `SettingsSupportLinkTests.swift`) so it
can't silently drift out of sync between the two. Nothing here to threat-model
beyond "is the URL still the literal we intend" — there's no server response
to validate, no invoice state, no credential.

## Out of scope (explicit)

- DRM / LCP-protected books — not supported; see v2 roadmap.
- Cloud sync of bookmarks/highlights — privacy contract forbids.
- Multi-account — not supported.
- iOS / desktop ports — separate workstreams. (Encrypted Vaults is Android-only so far — no iOS implementation exists yet.)
- Social engineering / coercion of the user into revealing a vault PIN or recovery key.
- Physical device access with the device unlocked and the vault already open (screen-lock and vault-lock are two separate boundaries; neither substitutes for the other).
- OS-level vulnerabilities in the Android sandbox, or a rooted device with the Keystore itself compromised.
- Side-channel attacks on TTS engine output (e.g., leaked speech-to-text content).
- Multi-user/role-based access within one vault — single PIN, single user (matches the rest of the app).
- No Pro/paid tier currently exists (the `core:licensing` module — Ed25519 key verification, Play Billing — was removed; all features, including Encrypted Vaults, are free on every flavor, donation-funded). A subscription is a possible future direction, not before there's a real install base to justify it.

## When to update this document

- Adding a new outbound call → add a row to the threat matrix.
- Adding a new parser surface → add a row referencing the fuzz harness.
- Adding a new persistent asset → add a row to the asset inventory.
- Adding a vault content-delivery adapter (EPUB/PDF/audio) → add a row to the threat matrix if it introduces a new parser/decode surface.

## References

- `SECURITY.md` — public-facing security policy.
- `CONTRIBUTING.md` — parser-input changes must come with a fuzz test.
- `.kilo/plans/1786595474979-encrypted-vaults-prd.md` — the Encrypted
  Vaults PRD: cipher/KDF selection rationale, key hierarchy, and the
  professional/regulatory positioning decision (§11.1 — "encrypted local
  storage for security-conscious professionals," never "HIPAA compliant").
- `core/storage/src/test/.../LibraryScannerImplTest.kt` — covers the
  enrichment gate and the cover-cache wipe recovery.
- `core/storage/src/test/.../CoverArtCacheTest.kt` — pins the
  `inSampleSize` cap and the 0×0 header rejection.
- `core/vaultcrypto/src/test/.../ChunkedVaultRoundTripTest.kt`,
  `.../TamperDetectionTest.kt`, `.../NonceUniquenessTest.kt`,
  `.../HeaderValidationTest.kt`, `.../VaultFormatVersionTest.kt` — the
  chunked AEAD format's round-trip, tamper-detection, nonce-determinism,
  and malformed-header coverage.
- `core/vaultcrypto/src/test/.../AesGcmKnownAnswerTest.kt` — a real
  Project Wycheproof AES-GCM test vector (not hand-derived).
- `core/vaultcrypto/src/test/.../VaultKeyManagerTest.kt`,
  `.../Argon2idKdfTest.kt` — the PIN/Argon2id/recovery-key wrapping
  hierarchy.
- `core/vaultstore/src/test/.../VaultStoreTest.kt`,
  `.../VaultSessionManagerTest.kt`,
  `.../VaultStoreHasNoLeakSurfaceDependencyTest.kt`,
  `.../VaultLeakClosureTest.kt`, `.../UnlockAttemptThrottleTest.kt`,
  `.../VaultRegistryTest.kt` — vault lifecycle, auto-lock, the
  no-plaintext-leak-surface guarantee, and anti-brute-force throttling.
- `feature/reader/src/test/.../EpubStripHtmlTest.kt` — 17 cases for
  the Jsoup HTML stripper including the 2 MB cap and CDATA / SVG /
  iframe / onload handling.
- `feature/settings/src/test/.../SupportLinkTest.kt`,
  `LibraVaultTests/SettingsSupportLinkTests.swift` — pin the exact
  external Support URL on each platform.