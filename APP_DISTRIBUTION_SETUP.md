# Firebase App Distribution Setup

## Overview
Firebase App Distribution enables fast beta testing without going through Google Play Store. Testers receive email invites and can install the APK via a secure link.

## Setup Steps

### 1. Add Testers
**Firebase Console** → **App Distribution** → **Testers and Groups**

- Click **+ Add testers**
- Enter email: `robster@robster.org`
- Click **Add**

### 2. Upload Release APK
**Firebase Console** → **App Distribution** → **New Release**

- Select app: LibraVault
- Upload APK: `/home/rob/git/LibraVault/reader/app/build/outputs/apk/play/release/libravault-fdroid-submission-prep-play-release.apk`
- Release notes: "Pocket-TTS integration - local-only on-device text-to-speech"
- Add tester group: Select `robster@robster.org`
- Click **Distribute**

### 3. Tester Installation
Tester receives email from Firebase with installation link → Opens link → Installs via Firebase app

## Current Status

**Release**: libravault-fdroid-submission-prep-play-release.apk (16MB)  
**Version**: Built from feature/pocket-tts-reader  
**Test Results**: 3/3 Firebase Test Lab devices passed ✅

**Tester**: robster@robster.org  
**Distributed**: [Date of distribution]

## Testing Checklist (for Tester)

### Smoke Tests
- [ ] App launches without crash
- [ ] No permission errors
- [ ] Settings UI accessible

### Pocket-TTS Functionality
- [ ] Engine selector shows "Pocket-TTS" option
- [ ] Can switch to Pocket-TTS from Android TTS
- [ ] Model downloads on first use
- [ ] Voice prompt plays
- [ ] Speech rate slider works
- [ ] Audio output works (headphones/speaker)

### Privacy/Network
- [ ] Disconnect WiFi, playback still works
- [ ] No unexpected network activity during playback

### Stability
- [ ] Engine switching doesn't crash
- [ ] Multiple playback start/stop cycles stable

## Next Steps
- [ ] Tester installs and runs smoke tests
- [ ] Report any crashes or issues
- [ ] Iterate and fix on feature branch if needed
- [ ] Merge to dev branch for release

---

**Setup Date**: 2026-07-21  
**Status**: Ready for distribution
