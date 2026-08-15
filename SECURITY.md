# Security Policy

## Supported versions

| Version | Supported |
|---|---|
| 0.2.x | ✅ Yes |
| 0.1.x | ✅ Yes |

## Reporting a vulnerability

Please do **not** open a public GitHub issue for security vulnerabilities.

Report security issues privately to: **security@libravault.xyz**

Include:
- Description of the vulnerability
- Steps to reproduce
- Potential impact
- Android version and device model if relevant

We aim to respond within 72 hours and to release a patch within 14 days for confirmed issues.

## Scope

Libravault has no server infrastructure and no user accounts, and makes **zero
network calls on any flavor** — the `INTERNET` permission is stripped from the
manifest entirely. (Previously the Play flavor made opt-in outbound HTTPS
calls to poll BTCPay for donation-invoice settlement; that flow was removed —
see "Support / donations" below.)
The attack surface is limited to:

- Malformed EPUB, PDF, or audio files processed by the app
- SAF URI permission handling
- Local data storage (Room database, SharedPreferences, cover art cache)
- Encrypted Vaults: the chunked AES-256-GCM file format, the PIN/Argon2id/
  recovery-key wrapping hierarchy, and the Android Keystore integration

Out of scope: social engineering, physical device access, OS-level vulnerabilities.

### Support / donations

There's no in-app donation code on any flavor or platform (Play, F-Droid,
iOS) — a single "Support the Project" button opens
`https://libravault.xyz/support.html` in the system browser. No address, QR
code, or payment logic exists inside the app itself, so there's no attack
surface here to defend: no API key to leak, no checkout link to validate, no
invoice state to corrupt. The website side (BTC/XMR addresses, BTCPay Server
checkout) is out of this app's threat model — it's a static site with its own
separate security posture.

There is currently no paid/Pro tier on any flavor — all features, including
Encrypted Vaults, are free and donation-funded. (A previous Ed25519-license-key
/ Play Billing system was removed; a subscription may return once there's a
real install base to justify it, but not before.)

## Threat model

The asset table below is the canonical list of things worth protecting.
Each row pairs an asset with the most plausible threat actor and the
mitigation that currently defends it. When proposing a change, check
this table first — if your change adds a new asset, add a row.

### Assets

| Asset | Where it lives | Sensitivity |
|---|---|---|
| User's EPUB / PDF / audiobook files | SAF-granted folders; never copied into the app sandbox | High (user's library) |
| Encrypted Vault content and manifest (files a user chose to encrypt) | App-private dir, chunked AES-256-GCM ciphertext; manifest (titles/authors/bookmarks/highlights/cover art) encrypted as one file | Highest — this is the one asset class the app makes an explicit confidentiality promise about |
| Vault Master Key | In memory only while unlocked; wrapped by a hardware-backed (StrongBox/TEE) Android Keystore key (PIN path) and independently by a 256-bit recovery key shown once at creation | High |
| Reading progress, bookmarks, highlights | Room `libravault.db` | Medium (no PII; user-identifying on restore) |
| Cover art cache | App cache dir, keyed by SHA-256 of source path | Low (regeneratable) |

### Threat actors

| Actor | Capability | Mitigation |
|---|---|---|
| **Malicious EPUB / PDF** | Crafts a file that exploits a parser bug to crash, RCE, or extract data | Jsoup-based HTML strip (WS3.6); `MediaMetadataRetriever` bounded by `withTimeout(10s)`; EPUB entry size hard-capped at 5 MB (WS3.5); `BitmapFactory.Options.inSampleSize` capped at 16 (WS3.1); `XmlPullParser.FEATURE_PROCESS_DOCDECL = false` blocks XXE (WS3.4); fuzz harness in CI for the parsers (WS7). |
| **Rooted device / offline image of `/data`** | Extracts app-private storage with the device off or locked, no live UI | Encrypted Vault content is never derivable from disk alone: the PIN-wrapped Vault Master Key additionally requires the non-exportable Keystore key (no offline brute-force path), and the recovery-key path requires a 256-bit key that was never persisted anywhere. |
| **Live device, repeated vault PIN guessing** | Attacker holds an unlocked-screen device, tries PINs in the vault UI | Exponential backoff after 3 free attempts (capped at 5 min); deliberately no auto-wipe on repeated failure. |
| **Sibling app with SAF grant to same folder** | Reads the user's library directly via SAF; nothing the app can do | Sandbox isolation; SAF grants are user-approved at folder level. Encrypted Vault content is never SAF-exposed at all — it lives in app-private storage. |
| **Screenshot / screen recording / casting while a vault is open** | Malware or a shoulder-surfer captures the screen | `FLAG_SECURE` — unconditional while a vault's recovery key is shown/entered, user-togglable (default on) for vault content otherwise. |
| **Side-channel via MediaMetadataRetriever** | Hangs the IO thread on malformed frames | `withTimeout(10_000)` covers the entire extract (WS3.3). |
| **EPUB with 500 MB cover image** | OOM during `BitmapFactory.decodeByteArray` | Cover entry size-capped at 5 MB in EPUB extract (WS3.5); `inSampleSize` capped at 16 (WS3.1); header that reports 0×0 is rejected before decode (WS3.1). |

### Per-flavor network posture

| Flavor | `INTERNET` permission | Outbound calls |
|---|---|---|
| `fdroid` | **Stripped** from manifest | None |
| `play` | **Stripped** from manifest | None |
