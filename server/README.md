# Libravault donation proxy

The Play flavor of the Android app needs to poll a BTCPay server for invoice
settlement. Rather than ship the BTCPay API key in the APK, the app talks to
this thin proxy. The proxy holds the BTCPay credentials and only exposes the
three endpoints the app needs.

## Endpoints

All endpoints require `Authorization: Bearer <PROXY_SHARED_SECRET>`.

| Method | Path | Body / Query | Returns |
|---|---|---|---|
| POST | `/donate/invoice` | `{"amountUsd": int}` | `{id, checkoutLink}` |
| GET  | `/donate/invoice/:id` | — | `{status: New\|Processing\|Settled\|Expired\|Invalid\|Unknown}` |
| GET  | `/donate/invoice/:id/payment/:coin` | `:coin` ∈ `BTC`,`XMR` | `{address, paymentLink, cryptoAmount}` or `null` |

`/donate/settled` is **removed** (was a leaky abstraction). Clients poll
`/donate/invoice/:id` until `status === "Settled"`.

## Deploy

1. `cp .env.example .env` and fill in:
   - `BTCPAY_API_KEY` — issued by your BTCPay under Store → Access Tokens.
     Needs `btcpay.store.cancreateinvoice` and `btcpay.store.canviewinvoices`.
   - `STORE_ID` — from Store → Settings → General.
   - `PROXY_SHARED_SECRET` — generate with
     `node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))"`.
     Paste the same value into the Android `BuildConfig` (or `secrets.xml`) so
     the app attaches it to every request.
   - `PORT` — default 3456, behind nginx.

2. Run behind nginx. Required nginx location block (TLS-terminated, real IP from
   the `X-Forwarded-For` header, body limit at 8 KB to allow some headroom):

   ```nginx
   location /donate/ {
       proxy_pass http://127.0.0.1:3456;
       proxy_set_header X-Forwarded-For $remote_addr;
       proxy_set_header Host $host;
       client_max_body_size 8k;
   }
   ```

3. Boot: `node proxy.js` (or use systemd / pm2 / docker). The server refuses to
   start if `PROXY_SHARED_SECRET` is shorter than 32 chars or any value is the
   placeholder string.

## Security model

- **Body cap:** 4 KB (Express `express.json({ limit: '4kb' })`). Anything larger
  gets 413.
- **Rate limit:** 60 req/min per IP globally, 10 req/min per IP per endpoint
  (`express-rate-limit`).
- **Auth:** `Authorization: Bearer <PROXY_SHARED_SECRET>` with constant-time
  string comparison. Wrong scheme, missing header, or wrong token → 401.
- **Invoice ID validation:** `^[A-Fa-f0-9]{16,128}$`. Anchored to BTCPay's
  actual 32-hex format with a wide upper bound for future API-generated
  prefixes.
- **Helmet:** sets `X-Content-Type-Options`, `X-Frame-Options`,
  `Strict-Transport-Security`, etc. CSP disabled because this is a JSON API,
  not a document context.
- **No outbound IP leaks:** the proxy only talks to `BTCPAY_URL`. If that
  domain is ever compromised, see SECURITY.md for the kill-switch procedure.

## Threat model summary

| Asset | Threat | Mitigation |
|---|---|---|
| BTCPay API key | Process compromise | Proxy runs unprivileged, behind nginx, no shell access. |
| PROXY_SHARED_SECRET | APK decompilation | Per-release rotation; constant-time compare; logging redacts. |
| Donation flow integrity | Replay of paid invoices | Each invoice id is bound to BTCPay's 32-hex random. |
| Server resources | Slow-loris / body flood | 4 KB body cap + per-endpoint rate limit. |

Full threat model: see `docs/threat-model.md` at the repo root.

## Test

```sh
npm test
```

Tests stub `fetch()` to bypass BTCPay upstream. The 13 cases cover: missing
auth, wrong auth, non-Bearer scheme, body cap, amount validation, hex invoice
ids, coin validation, removed `/settled` endpoint, unknown routes.

## Local dev

```sh
PROXY_SHARED_SECRET="$(node -e 'console.log(require(\"crypto\").randomBytes(32).toString(\"base64url\"))')" \
STORE_ID="TestStoreIdXYZ" \
BTCPAY_API_KEY="fake" \
node proxy.js
```