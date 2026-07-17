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

Out of scope: social engineering, physical device access, OS-level vulnerabilities.

## Threat model

The asset table below is the canonical list of things worth protecting.
Each row pairs an asset with the most plausible threat actor and the
mitigation that currently defends it. When proposing a change, check
this table first — if your change adds a new asset, add a row.

### Assets

| Asset | Where it lives | Sensitivity |
|---|---|---|
| User's EPUB / PDF / audiobook files | SAF-granted folders; never copied into the app sandbox | High (user's library) |
| License keys (Pro tier) | `libravault_pro.xml` EncryptedSharedPreferences | High (proves purchase) |
| Reading progress, bookmarks, highlights | Room `libravault.db` | Medium (no PII; user-identifying on restore) |
| Pending BTCPay invoice IDs | `libravault_prefs.xml` | Low (transient, scoped to one donation) |
| Donation crypto addresses | `feature/settings/src/fdroid/.../FdroidStaticDonationAddresses.kt` only — never in the Play APK | Low (public donation addresses) |
| Cover art cache | App cache dir, keyed by SHA-256 of source path | Low (regeneratable) |

### Threat actors

| Actor | Capability | Mitigation |
|---|---|---|
| **Malicious EPUB / PDF** | Crafts a file that exploits a parser bug to crash, RCE, or extract data | Jsoup-based HTML strip (WS3.6); `MediaMetadataRetriever` bounded by `withTimeout(10s)`; EPUB entry size hard-capped at 5 MB (WS3.5); `BitmapFactory.Options.inSampleSize` capped at 16 (WS3.1); `XmlPullParser.FEATURE_PROCESS_DOCDECL = false` blocks XXE (WS3.4); fuzz harness in CI for the parsers (WS7). |
| **Compromised BTCPay** or **MITM** | Returns malicious `checkoutLink` (e.g. `javascript:`, `intent:`) | DonateScreen validates `checkoutLink.startsWith("https://")` and shows the host inline before launching the browser (WS5.2). Proxy requires Bearer auth with `PROXY_SHARED_SECRET` and 4 KB body cap (WS2). |
| **Rooted device** | Reads `libravault_pro.xml` from the EncryptedSharedPreferences sandbox | Documented as out-of-scope; master key is AES-256-GCM, key scheme is per-install. If a device is rooted the attacker has effectively won already. |
| **Sibling app with SAF grant to same folder** | Reads the user's library directly via SAF; nothing the app can do | Sandbox isolation; SAF grants are user-approved at folder level. |
| **Over-the-shoulder clipboard sniff** | Views the BTC address the user is about to paste | `copyToClipboard` sets `EXTRA_IS_SENSITIVE` on Android 13+, suppressing the system clipboard preview (WS5.4). |
| **BTCPay API abuse / request flood** | Burn the proxy's CPU / memory | `express-rate-limit` (60 req/min/IP globally, 10 req/min/IP per endpoint); 4 KB body cap (WS2). |
| **Side-channel via MediaMetadataRetriever** | Hangs the IO thread on malformed frames | `withTimeout(10_000)` covers the entire extract (WS3.3). |
| **EPUB with 500 MB cover image** | OOM during `BitmapFactory.decodeByteArray` | Cover entry size-capped at 5 MB in EPUB extract (WS3.5); `inSampleSize` capped at 16 (WS3.1); header that reports 0×0 is rejected before decode (WS3.1). |

### Per-flavor network posture

| Flavor | `INTERNET` permission | Outbound calls |
|---|---|---|
| `fdroid` | **Stripped** from manifest | None |
| `play` | Declared | `https://<your-btcpay-host>/donate/*` only, with Bearer auth, body-capped, rate-limited, scheme-validated (WS2 + WS5.2). |

### Key rotation

Ed25519 key for Pro license verification is rotated by:
1. Generating a new keypair with `tools/gen_keypair.py`.
2. Shipping an app release with the new public key in `LicenseVerifier.PUBLIC_KEY_B64`.
3. Re-signing all outstanding Pro license tokens with the new private key.

The `pro:v1:` payload prefix is unchanged across rotations — no
client-side version bump is required. Old keys remain valid for any
client that hasn't upgraded.

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
