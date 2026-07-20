# Firebase Testing Setup Guide

## Overview
This document guides you through setting up Firebase Test Lab and App Distribution for testing the pocket-tts feature.

## Prerequisites
- Firebase project created (https://console.firebase.google.com)
- gcloud CLI installed and authenticated
- Release APK built (building now)

## Step 1: Firebase Project Setup

### 1.1 Create Firebase Project
```bash
# If not already done
firebase init
```

### 1.2 Enable Required APIs
Go to Firebase Console → Project Settings:
- Enable "Cloud Testing API"
- Enable "App Distribution API"

### 1.3 Create Service Account
```bash
gcloud iam service-accounts create firebase-testing
gcloud projects add-iam-policy-binding PROJECT_ID \
  --member=serviceAccount:firebase-testing@PROJECT_ID.iam.gserviceaccount.com \
  --role=roles/cloudtesting.admin
gcloud iam service-accounts keys create ~/firebase-key.json \
  --iam-account=firebase-testing@PROJECT_ID.iam.gserviceaccount.com
```

## Step 2: Build & Upload APK

### 2.1 Release APK Location
Once build completes:
```bash
ls -lh app/build/outputs/apk/release/app-release.apk
```

### 2.2 Upload to Firebase Test Lab
```bash
# Set up gcloud auth
export GOOGLE_APPLICATION_CREDENTIALS=~/firebase-key.json

# Run tests on real devices
gcloud firebase test android run \
  --app=app/build/outputs/apk/release/app-release.apk \
  --test-targets="class xyz.libravault.feature.reader.TtsPlaybackTest" \
  --device-ids=taimen,walleye,sailfish \
  --os-versions=11,12,13 \
  --locales=en_US \
  --orientations=portrait \
  --results-bucket=gs://YOUR-BUCKET \
  --results-dir=test-results-$(date +%s)
```

## Step 3: App Distribution (Beta Testing)

### 3.1 Add Testers
```bash
gcloud firebase appdistribution:ios-testers add \
  --file=testers.txt
```

### 3.2 Distribute APK
```bash
gcloud firebase appdistribution android-build dist \
  --app=YOUR_APP_ID \
  --release-notes="Pocket-TTS testing build - focus on audio quality" \
  --testers-file=testers.txt \
  app/build/outputs/apk/release/app-release.apk
```

## Step 4: Test Plan for Pocket-TTS

### Functional Tests
- [ ] App launches without crashes
- [ ] Pocket-TTS engine loads (check logcat for native library load)
- [ ] Audio playback works (headphone/speaker)
- [ ] Speech rate slider responds
- [ ] Engine switching (Android TTS ↔ Pocket TTS) stable

### Privacy/Network Tests
- [ ] No unexpected network calls (monitor with Network Profiler)
- [ ] Model downloaded on first use (check app cache)
- [ ] Subsequent playback is offline (disconnect network, test playback)
- [ ] No data sent to external servers (verify with Firewall analyzer)

### Compatibility Tests
- [ ] Works on ARM64 devices (primary target)
- [ ] Works on ARM32 devices (if available)
- [ ] Different Android versions (11, 12, 13, 14)
- [ ] Different device manufacturers (Pixel, Samsung, OnePlus)

### Audio Quality Tests
- [ ] Pocket-TTS audio quality vs Android TTS
- [ ] No stuttering or drops
- [ ] Consistent playback speed
- [ ] Multiple languages if supported (currently en-US only)

## Step 5: Monitoring & Logs

### Firebase Crashlytics
Enable in app/build.gradle:
```gradle
dependencies {
    implementation 'com.google.firebase:firebase-crashlytics-ktx'
}
```

### Logcat Monitoring
```bash
# Connect device and stream logs
adb logcat | grep -i "tts\|sherpa\|pocket"
```

## APK Details

**Current Build:**
- Branch: `feature/pocket-tts-reader`
- Pocket-TTS: Integrated (local-only, no network)
- Android TTS: Available as fallback
- Model: ~120MB (downloaded on first use)

**Release APK Checklist:**
- [ ] All 33 unit tests pass
- [ ] No lint warnings
- [ ] Pocket-TTS AAR included
- [ ] F-Droid manifest variant verified
- [ ] Privacy policy updated (if needed)

## Troubleshooting

### "Native library not found"
Check logcat for `libsherpa-onnx-jni.so` loading errors. AAR may not include correct architecture.

### "Model download fails"
Verify GitHub release URL in buildConfig is accessible and SHA-256 matches.

### "Audio not playing"
Check device audio permissions and whether AudioTrack can allocate buffer.

## References
- [Firebase Test Lab Docs](https://firebase.google.com/docs/test-lab)
- [App Distribution Docs](https://firebase.google.com/docs/app-distribution)
- [gcloud firebase commands](https://cloud.google.com/sdk/gcloud/reference/firebase)
