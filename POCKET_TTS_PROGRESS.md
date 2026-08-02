# Pocket-TTS Implementation Progress

## v1 status: RESOLVED (Android + iOS)

The AAR blocker and TODOs below are closed out on Android, and iOS now has a
Pocket TTS engine of its own for the first time. `PocketTtsEngine`
(Android) / `PocketTTSEngine` (iOS) both run real sherpa-onnx synthesis
end to end:

**Android**:
- `sherpa-onnx-android.aar` is real (prebuilt `.so` libraries downloaded from
  sherpa-onnx's GitHub Releases, not the earlier hand-written stub) - see
  `third-party/sherpa-onnx/AAR_RESOLUTION.md`.
- `PocketModelManager` downloads and extracts a real voice model (Piper VITS,
  public-domain LJSpeech voice - see `SHERPA_ONNX_SETUP.md` for the licensing
  reasoning), fixing two real bugs along the way: it was pointed at the wrong
  URL (the engine tarball, not a voice model) and used the wrong archive
  codec (gzip instead of the bzip2 sherpa-onnx actually ships).
- `PocketVoiceCatalog` was reworked from its original WAV-voice-cloning
  design (built around sherpa-onnx's own "Pocket TTS" model family, which
  turned out to be CC-BY-NC - non-commercial only, can't ship in this app) to
  describe the single bundled VITS voice instead.
- `PocketTtsEngine.initialize()/speak()/setSpeechRate()` call the real
  `OfflineTts` API (vendored as `core/tts/.../pocket/sherpa/Tts.kt`).

**iOS** (new):
- `PocketTTSEngine.swift` wraps the same sherpa-onnx C API (vendored as
  `Sources/KmpInterop/PocketTTS/SherpaOnnx.swift`) via a bridging header,
  streaming generated audio through `AVAudioEngine`/`AVAudioPlayerNode`.
- Two static xcframeworks (sherpa-onnx + its onnxruntime dependency) are
  fetched at build time by `third-party/sherpa-onnx/setup-ios.sh`, not
  committed (~230MB combined) - see `SHERPA_ONNX_SETUP.md`.
- The same voice model (same URL/checksum, same licensing reasoning) is
  bundled into the app at build time rather than downloaded on first use
  like Android - `Foundation` has no tar/bzip2 support, and the only
  on-device alternative uses a private Apple API. See
  `SHERPA_ONNX_SETUP.md`'s "iOS: why the model is bundled" section.
- `TTSEngineProtocol` gives both `TTSEngineBridge` (system voice) and
  `PocketTTSEngine` a shared shape; `AppState.ttsEngineType` (persisted,
  Settings > Text-to-Speech) drives `LibravaultDomainBridge.switchTTSEngine(to:)`,
  mirroring Android's `TtsEngineProvider`.

**Not done**: manual on-device testing on either platform (the architecture
is real now on both, but nobody has heard either one actually speak on
physical hardware yet - this PR's Android verification was full unit-test +
APK-assembly level, and iOS's was CI-build-level, not a live device/simulator
run with audio).

## Completed ✅

### Step 1: Build Infrastructure & Dependencies
- ✅ sherpa-onnx v1.13.4 build script created (`third-party/sherpa-onnx/build-aar.sh`)
- ✅ Android NDK r26 installed at `~/android-tools/android-ndk-r26` (638 MB)
- ✅ CMake 3.28.3 verified and ready
- ✅ Build configuration: `POCKET_TTS_MODEL_URL` buildConfigField added
- ✅ Gradle integration via `files()` dependency (workaround for FAIL_ON_PROJECT_REPOS)
- ⚠️ **Note**: Real AAR build needs investigation (produced APK instead of library AAR)

### Step 2: AndroidTtsEngine Hardening
- ✅ Added `requiresNetwork: Boolean` to `TtsVoiceInfo`
- ✅ Per-`speak()` voice re-validation prevents silent cloud routing
- ✅ New `validateSelectedVoice(): Result<Unit>` for Settings UI
- ✅ Voice list now includes network-requirement metadata
- ✅ Unit tests for voice validation

### Step 3: PocketPlayback (AudioTrack Streaming)
- ✅ PCM 16-bit, 24 kHz mono audio playback
- ✅ Generation counter prevents stale callbacks during engine swaps
- ✅ pause() / resume() with internal flag synchronization
- ✅ FloatArray → ShortArray conversion with clamping to 16-bit range
- ✅ Streaming API ready for sherpa-onnx output

### Step 4: PocketTtsEngine (Skeleton)
- ✅ Implements `TtsEngine` interface
- ✅ State machine (UNINITIALIZED → INITIALIZING → IDLE → PLAYING/PAUSED)
- ✅ Voice selection, speech rate, playback control
- ✅ Error handling with clear messaging
- ⚠️ **TODO**: Connect to sherpa-onnx OfflineTts once AAR available

### Step 7: TtsEngineFactory & Engine Routing
- ✅ `TtsEngineType` enum (ANDROID, POCKET_TTS)
- ✅ Build flavor awareness (fdroid always falls back to Android TTS)
- ✅ Factory pattern for clean engine switching
- ✅ Singleton lifecycle management via Hilt injection

### Global Rules Established
- ✅ Git branching: always use `feature/*` or `fix/*` branches
- ✅ Unit tests: all new code must have tests
- ⚠️ Local-only TTS: removed optional network-voice toggle (privacy-first)

### Step 5: PocketModelManager ✅
- ✅ ModelStatus enum (Idle → Downloading → Ready/Failed)
- ✅ SHA-256 verification logic
- ✅ Idempotent cache in `context.filesDir/pocket-tts/model/`
- ⚠️ **TODO**: Wire HTTP download + tar.gz extraction

### Step 6: PocketVoiceCatalog ✅
- ✅ Voice prompts management (WAV files)
- ✅ Surface as `TtsVoiceInfo` list with `requiresNetwork=false`
- ✅ Voice name formatting (kebab-case → Title Case)
- ⚠️ **TODO**: Copy bundled voices from assets

### Step 8: TtsPreferences & TtsEngineProvider ✅
- ✅ In-memory StateFlow-based preferences
- ✅ Engine type, voice selection, local-only toggle
- ✅ TtsEngineProvider reactive engine switching
- ⚠️ **TODO**: Add androidx.datastore dependency for persistence

### Step 9: TtsSettingsSection (Compose) ✅
- ✅ Engine selection radio group
- ✅ Build flavor awareness (fdroid hides Pocket TTS)
- ✅ Speech rate slider (0.5×–3.0×)
- ✅ Model download status UI structure
- ⚠️ **TODO**: Wire reactive state collection from providers

## Must-Do Items: RESOLVED ✅

### 1. Dependencies ✅
- androidx.datastore:datastore-preferences 1.0.0
- com.squareup.okhttp3:okhttp 4.11.0  
- org.apache.commons:commons-compress 1.24.0
- **Result**: All wired and tested

### 2. TODO Items ✅
- HTTP download (OkHttp) ✅
- Tar extraction (commons-compress) ✅
- Voice asset copying ✅
- Reactive flows (DataStore) ✅
- Settings UI composition ✅
- **Result**: All plumbing complete

### 3. AAR Blocker: MITIGATED ✅
- Created minimal functional AAR (compiles)
- Documented 3 resolution paths (AAR_RESOLUTION.md)
- Root cause: native libraries need NDK/CMake
- **Fallback**: Android TTS always available
- **Result**: Clear path forward, no longer blocking

## Testing Status

### Unit Tests (All Passing ✅)
- `AndroidTtsEngineTest`: 8 existing + 2 new (voice validation)
- `PocketPlaybackTest`: 4 tests (instantiation, lifecycle, clamping)
- `PocketTtsEngineTest`: 4 tests (interface, state machine)
- `TtsEngineFactoryTest`: 2 tests (enum, routing)
- `PocketModelManagerTest`: 4 tests (ModelStatus types, SHA256)
- `PocketVoiceCatalogTest`: 3 tests (voice formatting, locale)
- `TtsPreferencesTest`: 6 tests (engine type serialization)
- **Total**: 33 tests, 0 failures ✅

### Manual Testing
- Not yet (need real AAR)
- Planned for steps 5–9

## Build Configuration

**Complete ✅**:
- Core TTS module compiles with all dependencies ✅
- Unit tests pass (33 tests, 0 failures) ✅
- Stub AAR allows development to continue ✅
- HTTP download implementation ✅
- Tar extraction implementation ✅
- DataStore persistence ✅
- Reactive flows ✅
- Settings UI Compose scaffolding ✅

**Near-term (AAR dependent)**:
- Real native library bindings
- Model download verification
- Bundled voice asset testing
- End-to-end device testing

## Files Modified/Created

### Core Implementation
- `core/tts/src/main/kotlin/xyz/libravault/core/tts/TtsState.kt` (modified)
- `core/tts/src/main/kotlin/xyz/libravault/core/tts/AndroidTtsEngine.kt` (modified)
- `core/tts/src/main/kotlin/xyz/libravault/core/tts/TtsEngineFactory.kt` (new)
- `core/tts/src/main/kotlin/xyz/libravault/core/tts/pocket/PocketPlayback.kt` (new)
- `core/tts/src/main/kotlin/xyz/libravault/core/tts/pocket/PocketTtsEngine.kt` (new)

### Tests
- `core/tts/src/test/kotlin/xyz/libravault/core/tts/AndroidTtsEngineTest.kt` (modified)
- `core/tts/src/test/kotlin/xyz/libravault/core/tts/TtsEngineFactoryTest.kt` (new)
- `core/tts/src/test/kotlin/xyz/libravault/core/tts/pocket/PocketPlaybackTest.kt` (new)
- `core/tts/src/test/kotlin/xyz/libravault/core/tts/pocket/PocketTtsEngineTest.kt` (new)

### Build & Infrastructure
- `core/tts/build.gradle.kts` (modified for AAR reference)
- `settings.gradle.kts` (modified for JitPack, then reverted)
- `third-party/sherpa-onnx/build-aar.sh` (new)
- `third-party/sherpa-onnx/BUILD.md` (new)
- `third-party/sherpa-onnx/sherpa-onnx-android.aar` (real, built from sherpa-onnx's prebuilt release binaries)
- `SHERPA_ONNX_SETUP.md` (new, developer guide)
- `.kilo/plans/1783324829228-pocket-tts-reader-plan.md` (ref from kilocode)

## Next Steps

**Immediate** (Priority):
1. ✅ Implement steps 5–9 (all architecture layers complete)
2. ✅ Resolve sherpa-onnx AAR build issue
3. ✅ Add missing dependencies (DataStore, HTTP client)
4. ✅ Wire TODO items (model download, real synthesis calls)

**Then**:
1. Full integration testing on a real device (nothing has exercised this on
   actual hardware yet - JVM unit tests can't cross the JNI boundary)
2. Handle edge cases (network errors, low disk space)
3. Settings UI polish & testing (`TtsSettingsSection`'s download-progress UI
   is still a static placeholder - `PocketModelManager.ensureModelAvailable()`
   is real now and ready to wire up)

**Finally**:
1. Documentation updates (README, in-app licenses - the Piper/LJSpeech and
   sherpa-onnx Apache-2.0 attributions should surface somewhere user-facing)
2. CI/CD integration for AAR builds (`build-aar.sh` is fast now - a few
   seconds, no NDK - reasonable to run in CI instead of only committing the
   AAR, if reproducible-build concerns ever call for it)
3. Performance optimization (model caching, lazy loading)
4. ✅ iOS Pocket TTS parity (see "v1 status" above) - still needs real
   on-device audio verification, same as Android
