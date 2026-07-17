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
