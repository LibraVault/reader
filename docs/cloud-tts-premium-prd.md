# PRD: Premium Cloud TTS Voices (BYOK)

**Status:** Draft · **Author:** principal-dev-lead session, 2026-08-22 · **Base commit:** `890f2c1` (dev)
**Depends on:** [#397](https://github.com/LibraVault/reader/pull/397) (Play Billing), [#398](https://github.com/LibraVault/reader/pull/398) (StoreKit 2) — both merged; this is the first feature to actually consume the `subscriptionActive` signal they added.
**Governance sign-off:** [#449](https://github.com/LibraVault/reader/issues/449)

## 1. What this is

A Premium (subscriber-only) Read Aloud voice source: users bring their own
API key for a cloud TTS vendor and get that vendor's voices instead of (or
alongside) the existing on-device TTS engine. LibraVault never holds a key,
never proxies a call, and never sees usage cost — the user's key, the user's
bill, the user's choice of vendor.

## 2. Product decisions being made here (explicit reversals)

Recorded in full at [#449](https://github.com/LibraVault/reader/issues/449); summarized:

1. **Reverses the "no Pro tier" decision** (PR #172). This is the first
   LibraVault feature actually gated on `subscriptionActive`. Nothing else
   in the app becomes gated — donation/tip flows (#397/#398) stay exactly as
   shipped, ungated.
2. **Narrows a second reversal on top of #400.** #400 signed off on network
   calls to two fixed billing endpoints only. This adds calls to five named
   third-party TTS vendor APIs, sending reader-supplied text off-device.
   Scoped deliberately to fixed, named endpoints — no arbitrary
   custom/user-supplied host in v1 (see §3).

## 3. Scope

**v1 (this PRD):**
- Five presets, chosen for name recognition and stable REST APIs: **ElevenLabs, OpenAI, Google Cloud TTS, Azure AI Speech, Amazon Polly.**
- BYOK only — user pastes their own key per provider they enable.
- Non-streaming, per-paragraph synthesis (matches how the existing Read Aloud engine already segments text for on-device TTS — no new segmentation logic needed).
- Falls back to on-device TTS on any cloud error; never silently stalls playback.

**Explicitly deferred** (open a new issue if real user demand shows up):
- Custom/arbitrary-endpoint provider (user-supplied URL + generic request/response mapping).
- Any LibraVault-run proxy or shared/managed API key.
- In-app provider account creation, OAuth, or voice-cloning management UI.
- Cost/usage telemetry beyond a simple local "characters sent this session" counter.

## 4. Gating — two independent switches, both required

A cloud TTS call can fire only if **both** are true:

- (a) `subscriptionActive` — from `core:billing` (Android) / `StoreKitBillingManager` (iOS), the real signal #397/#398 added.
- (b) A **separate, explicit "Cloud Voices" consent toggle**, off by default, with its own disclosure screen — independent of the Premium purchase flow. Buying the subscription does not itself enable any network call.

## 5. Architecture

Mirrors the flavor-source-set shape already established by `core:billing`
(and, before it, the deleted `core:licensing`):

- **Android — new `core:cloudtts` module.** Shared `CloudTtsProvider`
  interface in `src/main`; the five vendor adapters (plain HTTP clients, no
  vendor SDKs needed) live in `src/play` (or whichever non-F-Droid flavor
  source set matches `core:billing`'s convention); F-Droid gets a `NoOp`
  implementation, zero new dependency, unit-tested the same way
  `NoOpBillingClient` is.
- **iOS — new `Sources/Features/CloudTTS/`.** Swift equivalent,
  `URLSession`-based per-vendor clients. Single distribution channel, so no
  flavor split, but still behind both gates in §4.
- **Provider interface:** `synthesize(text, voiceId, apiKey) -> AudioData`.
  Start non-streaming; revisit only if a specific vendor's latency proves it
  necessary.
- **Key storage:** Android Keystore-backed `EncryptedSharedPreferences` /
  iOS Keychain. Never plaintext prefs, never `Room`/`UserDefaults`, never
  logged — reuse this codebase's existing secure-storage conventions from
  `core:vaultcrypto` rather than rolling a new one.
- **Integration point:** an additional voice source in the existing Read
  Aloud engine (#137 EPUB, #138 player-screen parity, #276 Markdown),
  selectable per-provider once a key is configured — not a replacement for
  on-device TTS.

## 6. Consent / privacy UX

- New "Cloud Voices" section in Settings, visible only when
  `subscriptionActive` is true.
- First enable of any provider: a modal disclosure — plain language that
  enabling a cloud voice sends the text of what's being read to that
  vendor's servers to generate speech, that it's optional, and that it's off
  by default. Explicit accept required; never a pre-checked box.
- Per-provider key entry screen; key is validated with a single cheap
  test call, then stored, never transmitted anywhere but that vendor's own
  endpoint.
- `docs/threat-model.md` and `KNOWN_LIMITATIONS.md` need a new entry each —
  tracked here rather than reopening #400, since #400 already left the
  "no networking" README/onboarding copy as a known stale-claim follow-up
  from the billing reversal; this feature adds a second copy update to that
  same follow-up, not a new one.

## 7. Testing

- Per-adapter unit tests against recorded/mocked HTTP responses — no real
  network calls in CI, same approach as `PlayBillingClientImplTest`.
- Four-quadrant gate test: (subscription × consent) on/off, confirming a
  network call only fires when both are true.
- Secure-storage round-trip tests (write key, read key, confirm it never
  appears in a plaintext store or log).
- Fallback-to-on-device-TTS-on-error test for each adapter.
- Regression test pinning the five fixed endpoint hosts, so a future change
  can't silently add a sixth or turn one into a user-configurable value
  without that being a deliberate, reviewed diff.

## 8. Rollout

- Ships the same way #397/#398 did: pre-gated on real conditions rather than
  a manual flag. Since subscription products don't exist in App Store
  Connect / Play Console yet, `subscriptionActive` is always `false` today —
  this feature is inert but safe to merge ahead of that store setup
  completing.
- F-Droid unaffected — no new dependency on that flavor at all, verified the
  same way `core:billing` was (`:app:dependencies --configuration
  fdroidDebugRuntimeClasspath` should not show any of the five vendor HTTP
  clients).

## 9. Open questions (not blocking v1)

- Custom/arbitrary provider endpoint — revisit if requested.
- Whether a future managed/proxied tier ever makes sense — explicitly
  rejected for v1 (BYOK only, see [#449](https://github.com/LibraVault/reader/issues/449)).
