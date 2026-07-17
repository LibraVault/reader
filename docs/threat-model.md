# LibraVault threat model

This document is the engineering-side complement to `SECURITY.md`. It is
the source of truth for:

- What assets LibraVault protects (and what we explicitly don't).
- Which threat actors we design against, and which are out of scope.
- The mitigation that defends each asset, with a code reference.
- The donation-flow sequence diagram (Play flavor only).

Update this file whenever you add a new asset, a new outbound call,
or a new parser surface. The "Test coverage" column links to the
test file that proves the mitigation is in place.

## Asset inventory

| Asset | Storage | Test coverage |
|---|---|---|
| User library files | SAF folders (never copied) | `core/storage/src/test/.../LibraryScannerImplTest.kt` |
| License keys | `libravault_pro.xml` (EncryptedSharedPreferences, AES-256-GCM master key) | `core/licensing/src/test/.../LicenseVerifierTest.kt` |
| Reading progress / bookmarks / highlights | Room `libravault.db` | `feature/reader/src/test/.../ReaderViewModelTest.kt` |
| Pending BTCPay invoice IDs | `libravault_prefs.xml` | (no test — preference I/O) |
| Cover art cache | App cache, SHA-256 keyed | `core/storage/src/test/.../CoverArtCacheTest.kt` |
| Donation crypto addresses | `fdroid/.../FdroidStaticDonationAddresses.kt` only | (compile-time check that Play APK has no strings match) |

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
| Compromised BTCPay returns `javascript:` checkoutLink | MITM or BTCPay compromised | 1 | 4 | `safeCheckoutLink` validates scheme = `https` and host non-blank; UI shows host before launching | `feature/settings/.../DonateScreen.kt:safeCheckoutLink`, `feature/settings/src/test/.../SafeCheckoutLinkTest.kt` |
| Donation button double-tap creates 2 invoices | User taps "Get Payment Address" rapidly | 4 | 2 | Button disabled while `DonationState.Creating` | `feature/settings/.../DonateScreen.kt` |
| App leaks F-Droid BTC address into Play APK | String table contains both sourceSets' consts | 2 | 1 | `StaticDonationAddresses` interface; only `fdroid/.../FdroidStaticDonationAddresses.kt` ships the real strings | `feature/settings/src/fdroid/.../FdroidStaticDonationAddresses.kt` |
| Crypto address leaked via clipboard preview | User copies address on Android 13+; system shows preview | 4 | 1 | `EXTRA_IS_SENSITIVE` set on ClipDescription | `feature/settings/.../SettingsScreen.kt:copyToClipboard` |
| Cold-launch ACTION_VIEW intent dropped silently | `LaunchedEffect(nav)` races with `onNewIntent` when nav is null | 2 | 3 | `pendingIntent` as `mutableStateOf<Intent?>`; consumer keyed on (pendingIntent, nav) | `app/.../MainActivity.kt` |
| Bookmark DB row with bad color hex wipes all highlights | User restores from backup with corrupt `colorHex` | 2 | 3 | Explicit try/catch + log for IllegalArgumentException / JSONException per highlight | `feature/reader/.../EpubReaderScreen.kt` |
| BTCPay proxy flooded | Single IP sends 1000 r/s | 3 | 3 | `express-rate-limit` 60 r/min global, 10 r/min per endpoint; 4 KB body cap | `server/proxy.js` |
| License verifier accepts placeholder public key | Production ships with the literal `REPLACE_WITH_...` constant | 2 | 3 | `verify()` short-circuits to `Invalid("Server public key not configured")` if `PUBLIC_KEY_B64` starts with `REPLACE_WITH_` | `core/licensing/.../LicenseVerifier.kt`, `core/licensing/src/test/.../LicenseVerifierTest.kt` |
| Enrichment scope never cancelled | `LibraryScannerImpl` runs forever on a 10 k-item library | 4 | 2 | `backgroundScope` is now lifecycle-bound (TODO WS3.8); cancellation hook on next scan via `enrichmentJob?.cancel()` | `core/storage/.../LibraryScannerImpl.kt` |

## Donation flow (Play flavor)

```mermaid
sequenceDiagram
    participant U as User
    participant DS as DonateScreen
    participant SVM as SettingsViewModel
    participant BPC as BtcPayClient (Play)
    participant Proxy as donate-proxy
    participant BTC as BTCPay Server

    U->>DS: Tap "Donate"
    DS->>SVM: createDonationInvoice(amount, coin)
    SVM->>SVM: _donationState = Creating; button disabled
    SVM->>BPC: createInvoice(amountUsd)
    BPC->>Proxy: POST /donate/invoice (Bearer, 4 KB cap)
    Proxy->>Proxy: validate Bearer (constant-time)
    Proxy->>BTC: POST /api/v1/stores/{id}/invoices
    BTC-->>Proxy: {id, checkoutLink}
    Proxy-->>BPC: {id, checkoutLink}
    BPC-->>SVM: NewInvoice(id, checkoutLink)
    SVM->>BPC: getPaymentInfo(invoice.id, coin)
    BPC->>Proxy: GET /donate/invoice/:id/payment/:coin
    Proxy->>BTC: GET /api/v1/stores/{id}/invoices/:id/payment-methods
    alt coin is BTC or XMR
        BTC-->>Proxy: payment-methods[]
        Proxy-->>BPC: {address, paymentLink, cryptoAmount}
        BPC-->>SVM: InvoicePaymentInfo
        SVM->>SVM: _donationState = Pending
        Note over SVM: pollUntilPaid() loops every 15 s
        SVM->>BPC: getInvoiceStatus(id) (loop)
        BPC->>Proxy: GET /donate/invoice/:id
        Proxy->>BTC: GET /api/v1/stores/{id}/invoices/:id
        BTC-->>Proxy: {status}
        alt status == Settled
            Proxy-->>BPC: {status: "Settled"}
            BPC-->>SVM: InvoiceStatus.Settled
            SVM->>SVM: setSupporter(true); _donationState = Paid
        else status == Expired/Invalid
            Proxy-->>BPC: {status: ...}
            BPC-->>SVM: InvoiceStatus.Expired
            SVM->>SVM: _donationState = Idle
        else status == New/Processing/Unknown
            Proxy-->>BPC: {status: ...}
            BPC-->>SVM: InvoiceStatus.New
            Note over SVM: continue polling
        end
    else no method
        BTC-->>Proxy: []
        Proxy-->>BPC: null
        BPC-->>SVM: null
        alt Play flavor (no static fallback)
            SVM->>SVM: _donationState = Error("BTCPay has no X method")
        else F-Droid flavor (has static fallback)
            SVM->>SVM: _donationState = NoMethod(staticAddress)
        end
    end

    alt checkoutLink present
        U->>DS: Tap "Open checkout"
        DS->>DS: safeCheckoutLink(link) — scheme = https only
        alt safe
            DS->>U: Intent(ACTION_VIEW, Uri) opens browser
        else unsafe
            DS->>U: button hidden
        end
    end
```

## Out of scope (explicit)

- DRM / LCP-protected books — not supported; see v2 roadmap.
- Cloud sync of bookmarks/highlights — privacy contract forbids.
- Multi-account — not supported.
- iOS / desktop ports — separate workstreams.
- Social engineering of the user into revealing a license key.
- Physical device access (rooted device, lost device with screen unlocked).
- OS-level vulnerabilities in the Android sandbox.
- Side-channel attacks on TTS engine output (e.g., leaked speech-to-text content).

## When to update this document

- Adding a new outbound call → add a row to the threat matrix.
- Adding a new parser surface → add a row referencing the fuzz harness.
- Adding a new persistent asset → add a row to the asset inventory.
- Changing the donation flow → update the Mermaid sequence diagram.
- Rotating the Ed25519 key → note the rotation in SECURITY.md.

## References

- `SECURITY.md` — public-facing security policy.
- `CONTRIBUTING.md` — parser-input changes must come with a fuzz test.
- `core/storage/src/test/.../LibraryScannerImplTest.kt` — covers the
  enrichment gate and the cover-cache wipe recovery.
- `core/storage/src/test/.../CoverArtCacheTest.kt` — pins the
  `inSampleSize` cap and the 0×0 header rejection.
- `core/licensing/src/test/.../LicenseVerifierTest.kt` — roundtrip + 4
  negative cases for the Ed25519 license verifier.
- `feature/reader/src/test/.../EpubStripHtmlTest.kt` — 17 cases for
  the Jsoup HTML stripper including the 2 MB cap and CDATA / SVG /
  iframe / onload handling.
- `feature/settings/src/test/.../SafeCheckoutLinkTest.kt` — 13 cases
  for `safeCheckoutLink` (https-only, no `javascript:` / `intent:` /
  `file:` / `content:`).
- `feature/settings/src/test/.../StaticAddressesTest.kt` — covers the
  per-flavor address fallback decision.
- `server/test/proxy.test.js` — 10 cases for the Express proxy
  (auth, body cap, rate limit, hex invoice IDs, coin validation).
- `tools/README.md` — Ed25519 keypair generation and signing.