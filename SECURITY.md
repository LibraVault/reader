# Security Policy

## Supported versions

| Version | Supported |
|---|---|
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

Libravault has no server infrastructure, no network communication, and no user accounts.
The attack surface is limited to:

- Malformed EPUB, PDF, or audio files processed by the app
- SAF URI permission handling
- Local data storage (Room database, SharedPreferences, cover art cache)

Out of scope: social engineering, physical device access, OS-level vulnerabilities.
