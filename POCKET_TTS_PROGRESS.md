# Pocket-TTS Implementation Progress

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

## In Progress ⏳

### Step 5: PocketModelManager
- Not yet started
- Will download & verify tarball from GitHub release
- SHA-256 verification against manifest
- Caching in `context.filesDir/pocket-tts/model/`

### Step 6: PocketVoiceCatalog
- Not yet started
- Bundle `bria.wav` (CC-BY) voice prompt
- Surface as `TtsVoiceInfo` list

### Step 8: TtsPreferences & TtsEngineProvider
- Not yet started
- DataStore-backed preferences (engine type, voice, local-only setting)
- StateFlow provider for reactive engine switching

### Step 9: Settings UI (TtsSettingsSection)
- Not yet started
- Engine selection radio group
- Voice picker with network badges
- Speech rate slider
- Download progress indicator for Pocket TTS model

## Blocked/Known Issues ⚠️

### sherpa-onnx AAR Build
**Issue**: Build script produced APK instead of library AAR
- Gradle build succeeded but couldn't locate .aar output
- Reference app (SherpaOnnxTtsEngine) is an application, not library module

**Options**:
1. Extract native .so libraries from APK and wrap in minimal AAR
2. Find/build separate library module from sherpa-onnx source
3. Use pre-built Maven artifact (needs verification on current availability)

**Action**: Investigate sherpa-onnx project structure for library module or native bindings

## Testing Status

### Unit Tests (All Passing ✅)
- `AndroidTtsEngineTest`: 8 existing + 2 new (voice validation)
- `PocketPlaybackTest`: 4 tests (instantiation, lifecycle, clamping)
- `PocketTtsEngineTest`: 4 tests (interface, state machine)
- `TtsEngineFactoryTest`: 2 tests (enum, routing)
- **Total**: 20 tests, 0 failures

### Manual Testing
- Not yet (need real AAR)
- Planned for steps 5–9

## Build Configuration

**Working**:
- Core TTS module compiles ✅
- Unit tests pass ✅
- Stub AAR (minimal test artifact) works ✅

**Pending**:
- Real sherpa-onnx AAR integration
- Model download & verification
- Settings UI Compose integration

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
- `third-party/sherpa-onnx/sherpa-onnx-android.aar` (stub)
- `SHERPA_ONNX_SETUP.md` (new, developer guide)
- `.kilo/plans/1783324829228-pocket-tts-reader-plan.md` (ref from kilocode)

## Next Steps

**Immediate** (Priority):
1. Resolve sherpa-onnx AAR build issue
2. Implement PocketModelManager (step 5)
3. Implement PocketVoiceCatalog (step 6)

**Then**:
4. TtsPreferences & TtsEngineProvider (step 8)
5. Settings UI integration (step 9)
6. Full integration testing on device

**Finally**:
7. Documentation updates (README, in-app licenses)
8. CI/CD integration for AAR builds
