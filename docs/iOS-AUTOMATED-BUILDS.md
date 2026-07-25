# iOS Automated Builds via GitHub Actions

> **⚠️ SUPERSEDED (2026-07-25).** This document described an earlier, non-working version of the iOS CI/CD pipeline (`swift build` against a SwiftPM library, `macos-14`/Xcode 15.3, `ios-testflight.yml` auto-triggering on every push). None of that reflects current reality: the app now builds via a real Xcode App target on `macos-26`/Xcode 26.5, and `ios-testflight.yml` is manual-trigger only by design.
>
> For the accurate, currently-working process, see **[iOS-TESTFLIGHT-RELEASE-PROCESS.md](iOS-TESTFLIGHT-RELEASE-PROCESS.md)**.
>
> This file is kept only so old links don't 404; its original content has been removed to avoid anyone following stale instructions.
