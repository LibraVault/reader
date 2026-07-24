# Firebase Testing Checklist for Pocket-TTS

## Pre-Firebase Setup (Local)
- [x] Pocket-TTS architecture implemented (steps 1-9)
- [x] Dependencies added (DataStore, OkHttp, commons-compress)
- [x] TODO items wired (HTTP download, tar extraction, voice assets)
- [x] AAR blocker resolved (real native libraries built)
- [x] 33 unit tests passing
- [x] Build verified with real AAR
- [ ] Release APK built (in progress)
- [ ] Commit changes to feature/pocket-tts-reader branch

## Firebase Setup (Your Tasks)
- [ ] Firebase project created at console.firebase.google.com
- [ ] gcloud CLI installed and authenticated
- [ ] Service account created with Cloud Testing API access
- [ ] Firebase key downloaded (firebase-key.json)
- [ ] Test Lab and App Distribution APIs enabled

## Test Lab Setup (After APK Ready)
- [ ] Release APK uploaded to Test Lab
- [ ] Device selection: ARM64 primary (Pixel, Nexus)
- [ ] OS versions: Android 11, 12, 13, 14
- [ ] Test configuration complete
- [ ] Test run initiated

## Test Execution Plan

### Tier 1: Smoke Tests (Quick validation)
**Duration**: 15-20 minutes
- [ ] App launches without crash
- [ ] No immediate errors in logcat
- [ ] Can access Settings UI

### Tier 2: Pocket-TTS Functionality (Core feature)
**Duration**: 30-45 minutes
- [ ] Pocket-TTS engine selectable in Settings
- [ ] Model downloads on first use (check logcat)
- [ ] Model verification passes (SHA-256)
- [ ] Voice prompt loads (bria.wav)
- [ ] AudioTrack initializes
- [ ] Audio playback works (headphones/speaker)
- [ ] Speech rate slider updates playback speed
- [ ] Stop/pause/resume controls work

### Tier 3: Privacy & Network (Critical requirement)
**Duration**: 20-30 minutes
- [ ] Disconnect network, playback still works
- [ ] No HTTP requests during playback (Network Profiler)
- [ ] Model cached after first download
- [ ] No external URLs in network traffic

### Tier 4: Stability (Engine switching)
**Duration**: 15-20 minutes
- [ ] Switch Android TTS → Pocket TTS mid-playback: no crash
- [ ] Switch Pocket TTS → Android TTS mid-playback: no crash
- [ ] Multiple rapid switches: stable
- [ ] Memory leaks check (monitor Logcat for leak warnings)

### Tier 5: Compatibility (Multiple devices)
**Duration**: 30-45 minutes per device
- [ ] ARM64 devices (primary)
- [ ] ARM32 devices (if available)
- [ ] x86/x86_64 devices (if testing)
- [ ] Different manufacturers (Pixel, Samsung, OnePlus)

## Expected Results

### Success Criteria
- ✅ No crashes in Crashlytics
- ✅ Model downloads successfully
- ✅ Audio plays without stuttering
- ✅ Playback works offline (network disconnect)
- ✅ Engine switch stable (no crashes)
- ✅ Works on ARM64 Android 11+

### Failure Scenarios to Watch
- ❌ libsherpa-onnx-jni.so not found → AAR architecture mismatch
- ❌ Model SHA-256 mismatch → corrupted download
- ❌ AudioTrack initialization fails → buffer size too large
- ❌ Network calls during playback → privacy violation
- ❌ Memory spike on engine load → OOM on low-end devices

## Post-Test Actions

### If Tests Pass ✅
1. Merge feature/pocket-tts-reader → dev
2. Deploy to beta (via App Distribution)
3. Gather user feedback
4. Plan production release

### If Tests Fail ❌
1. Collect crash logs from Crashlytics
2. Review logcat from failed devices
3. Identify root cause (architecture, model, audio, network)
4. Fix on feature branch
5. Rebuild APK and re-test

## Contabo VPS Notes
- Use for emulator testing if needed
- Sufficient resources (CPU, RAM, disk)
- Can set up Android Studio emulator
- Run automated UITests against emulator
- Faster iteration than cloud-only testing

## Success Metrics
| Metric | Target | Current |
|--------|--------|---------|
| Crash rate | 0% | TBD |
| Audio quality | High | TBD |
| Model download time | <2 min | TBD |
| Playback latency | <100ms | TBD |
| Memory usage | <400MB | TBD |
| Network during playback | 0KB | TBD |

---

**Next Step**: Share release APK location once build completes, then proceed with Firebase setup.
