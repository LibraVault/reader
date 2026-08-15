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

Libravault has no server infrastructure and no user accounts. The Play flavor uses outbound HTTPS solely to poll BTCPay for donation-invoice settlement — this is opt-in (you must tap Donate first) and the only network destination is your own BTCPay server. The F-Droid flavor has **no network capability at all** (the `INTERNET` permission is stripped from its manifest).
The attack surface is limited to:

- Malformed EPUB, PDF, or audio files processed by the app
- SAF URI permission handling
- Local data storage (Room database, SharedPreferences, cover art cache)
- BTCPay API responses (Play flavor only, opt-in)
- Encrypted Vaults: the chunked AES-256-GCM file format, the PIN/Argon2id/
  recovery-key wrapping hierarchy, and the Android Keystore integration

Out of scope: social engineering, physical device access, OS-level vulnerabilities.

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
| Encrypted Vault content and manifest (files a user chose to encrypt) | App-private dir, chunked AES-256-GCM ciphertext; manifest (titles/authors/highlights/cover art) encrypted as one file | Highest — this is the one asset class the app makes an explicit confidentiality promise about |
| Vault Master Key | In memory only while unlocked; wrapped by a hardware-backed (StrongBox/TEE) Android Keystore key (PIN path) and independently by a 256-bit recovery key shown once at creation | High |
| Reading progress, bookmarks, highlights | Room `libravault.db` | Medium (no PII; user-identifying on restore) |
| Pending BTCPay invoice IDs | `libravault_prefs.xml` | Low (transient, scoped to one donation) |
| Donation crypto addresses | `feature/settings/src/fdroid/.../FdroidStaticDonationAddresses.kt` only — never in the Play APK | Low (public donation addresses) |
| Cover art cache | App cache dir, keyed by SHA-256 of source path | Low (regeneratable) |

### Threat actors

| Actor | Capability | Mitigation |
|---|---|---|
| **Malicious EPUB / PDF** | Crafts a file that exploits a parser bug to crash, RCE, or extract data | Jsoup-based HTML strip (WS3.6); `MediaMetadataRetriever` bounded by `withTimeout(10s)`; EPUB entry size hard-capped at 5 MB (WS3.5); `BitmapFactory.Options.inSampleSize` capped at 16 (WS3.1); `XmlPullParser.FEATURE_PROCESS_DOCDECL = false` blocks XXE (WS3.4); fuzz harness in CI for the parsers (WS7). |
| **Compromised BTCPay** or **MITM** | Returns malicious `checkoutLink` (e.g. `javascript:`, `intent:`) | DonateScreen validates `checkoutLink.startsWith("https://")` and shows the host inline before launching the browser (WS5.2). Proxy requires Bearer auth with `PROXY_SHARED_SECRET` and 4 KB body cap (WS2). |
| **Rooted device / offline image of `/data`** | Extracts app-private storage with the device off or locked, no live UI | Encrypted Vault content is never derivable from disk alone: the PIN-wrapped Vault Master Key additionally requires the non-exportable Keystore key (no offline brute-force path), and the recovery-key path requires a 256-bit key that was never persisted anywhere. |
| **Live device, repeated vault PIN guessing** | Attacker holds an unlocked-screen device, tries PINs in the vault UI | Exponential backoff after 3 free attempts (capped at 5 min); deliberately no auto-wipe on repeated failure. |
| **Sibling app with SAF grant to same folder** | Reads the user's library directly via SAF; nothing the app can do | Sandbox isolation; SAF grants are user-approved at folder level. Encrypted Vault content is never SAF-exposed at all — it lives in app-private storage. |
| **Over-the-shoulder clipboard sniff** | Views the BTC address the user is about to paste | `copyToClipboard` sets `EXTRA_IS_SENSITIVE` on Android 13+, suppressing the system clipboard preview (WS5.4). |
| **Screenshot / screen recording / casting while a vault is open** | Malware or a shoulder-surfer captures the screen | `FLAG_SECURE` — unconditional while a vault's recovery key is shown/entered, user-togglable (default on) for vault content otherwise. |
| **BTCPay API abuse / request flood** | Burn the proxy's CPU / memory | `express-rate-limit` (60 req/min/IP globally, 10 req/min/IP per endpoint); 4 KB body cap (WS2). |
| **Side-channel via MediaMetadataRetriever** | Hangs the IO thread on malformed frames | `withTimeout(10_000)` covers the entire extract (WS3.3). |
| **EPUB with 500 MB cover image** | OOM during `BitmapFactory.decodeByteArray` | Cover entry size-capped at 5 MB in EPUB extract (WS3.5); `inSampleSize` capped at 16 (WS3.1); header that reports 0×0 is rejected before decode (WS3.1). |

### Per-flavor network posture

| Flavor | `INTERNET` permission | Outbound calls |
|---|---|---|
| `fdroid` | **Stripped** from manifest | None |
| `play` | Declared | `https://<your-btcpay-host>/donate/*` only, with Bearer auth, body-capped, rate-limited, scheme-validated (WS2 + WS5.2). |

### When the proxy is compromised

If `PROXY_SHARED_SECRET` leaks (compromised CI, decompilation of a
release APK, log file exposure):

1. Generate a new secret: `node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))"`.
2. Update `server/.env` and ship a coordinated APK release with the new
   value embedded in `BuildConfig`.
3. Rotate the BTCPay API key in BTCPay → Store → Access Tokens (the
   proxy holds this credential and is the only thing that talks to
   BTCPay).
4. Audit server logs for `/donate/invoice/:id` traffic patterns and
   flag any settled invoices whose `id` doesn't match a server-issued
   record.
