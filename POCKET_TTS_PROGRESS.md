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
- `PocketModelManagerTest`: 4 tests (ModelStatus types, SHA256)
- `PocketVoiceCatalogTest`: 3 tests (voice formatting, locale)
- `TtsPreferencesTest`: 6 tests (engine type serialization)
- **Total**: 33 tests, 0 failures ✅

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
1. ✅ Implement steps 5–9 (all architecture layers complete)
2. Resolve sherpa-onnx AAR build issue
3. Add missing dependencies (DataStore, HTTP client)
4. Wire TODO items (model download, voice assets, etc.)

**Then**:
1. Full integration testing on device
2. Handle edge cases (network errors, low disk space)
3. Settings UI polish & testing

**Finally**:
1. Documentation updates (README, in-app licenses)
2. CI/CD integration for AAR builds
3. Performance optimization (model caching, lazy loading)
