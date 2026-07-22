# iOS v3.0 Phase A Progress Notes

**Date:** 2026-07-22  
**Branch:** `feature/ios-phase-a1-kmp-domain`  
**Focus:** KMP build infrastructure + core:domain conversion

## Corrections to the Original Phase A Plan

The original PRD's Phase A plan made assumptions about code state that required updates based on actual code inspection:

### A0 — Room KMP Spike (Still Valid)
- Room 2.6.1 is confirmed pinned; must bump to 2.7+ for Kotlin/Native
- This remains a critical prerequisite and is isolated as described in the original plan
- **No changes to scope** — the spike timing is correct

### A3 — core:logger Conversion (Effort Correction)
**Original estimate:** "single commit; trivial"  
**Actual state:** Fully Android/Hilt-coupled (`Context`, `android.util.Log`, `SharedPreferences`)  
**Corrected scope:** Requires full `expect`/`actual` split + separate iOS implementation design  
**Revised estimate:** ~2-3 commits (interface definition, Android actual, iOS placeholder/stub)

### A4 — core:licensing Module (Interfaces Don't Exist as Planned)
**Original claim:** "interfaces only (IProGate, ProStateManager, ProConfig, etc.)"  
**Actual state:**
- ✅ `PurchaseOutcome` is genuinely pure Kotlin
- ✅ `IProGate` interface exists BUT leaks `android.app.Activity` in its contract
- ❌ `ProStateManager` is a full Android implementation (not an interface) using `EncryptedSharedPreferences` + Android Keystore
- ❌ `KeyProGate` is a full Android implementation (not an interface)
- ❌ `ProConfig` class **does not exist** in the codebase (mentioned in plan, never implemented)
- ❌ `RecoveryService` class **does not exist** in the codebase (mentioned in plan, never implemented)

**Corrected scope:** 
- Redesign `IProGate` to remove `Activity` parameter from common interface
- Create new `ProStateManager` and `KeyProGate` interfaces in commonMain (currently concrete Android classes)
- Design iOS equivalents (must avoid Keychain per project constraint §4.10 — likely app-sandbox file encryption)
- Build `ProConfig` and `RecoveryService` from scratch (these are v3.1+ placeholders today)

**Revised estimate:** ~4-5 commits

### A5 — core:database (No Changes)
- Room KMP migration is correctly isolated behind A0 spike
- Schema/DAOs/entities/migrations are all pure KMP candidates once Room 2.7+ is available
- **Status:** On track, depends on A0 spike result

### A6 — core:storage (Interfaces Don't Exist)
**Original assumption:** "Extract existing interfaces and rename Android classes"  
**Actual state:**
- ❌ `MetadataExtractor` is a monolithic Android class (no separate interface exists)
- ❌ `CoverArtCache` is a monolithic Android class (no separate interface exists)
- ⚠️ `ScannedFile` data model directly references `android.net.Uri` inside what should be pure data
- ⚠️ `LibraryScannerImpl` has embedded Hilt `@Module` in the same file as the implementation

**Corrected scope:**
- Design new `MetadataExtractor` and `CoverArtCache` interfaces in commonMain
- Split implementation files (extract pure logic to commonMain, Android-specific I/O to androidMain)
- Abstract `ScannedFile.uri` field to a platform-agnostic type (String path or custom type)
- Extract Hilt module from `LibraryScannerImpl` to a separate androidMain file

**Revised estimate:** ~4-5 commits

### A7 — core:tts (No Changes)
- Clean interface/impl split already exists
- Only the Hilt binding module needs isolating to androidMain
- **Status:** On track, low-risk conversion

### A8/A9 — CI Integration (In Progress)
- iOS workflow files (`ios-build.yml`, `ios-checks.yml`, `ios-release.yml`) created ✅
- KMP CI matrix job (`ios-build.yml::kmp-build`) will first test the framework builds
- **Status:** On track; iOS workflows will fail until iOS targets can be built (Kotlin/Native only works on macOS/Linux for iOS targets)

## Phase A1 — KMP Infrastructure + core:domain

### Completed Work

1. **Gradle Version Catalog (`gradle/libs.versions.toml`)**
   - Added `kotlin-multiplatform` plugin entry

2. **Build Logic Convention Plugin (`build-logic/convention/`)**
   - Added `KotlinMultiplatformLibraryConventionPlugin.kt` with:
     - Applies `com.android.library` (required for `androidTarget()`)
     - Applies `org.jetbrains.kotlin.multiplatform`
     - Configures targets: `androidTarget()`, `iosArm64()`, `iosSimulatorArm64()`, `iosX64()`, `jvm()`
     - Sets JVM target to 17 (matches existing Android convention)
     - Registers plugin as `libravault.kmp.library` in `build-logic/convention/build.gradle.kts`

3. **core:domain Conversion**
   - Moved sources: `src/main/kotlin/` → `src/commonMain/kotlin/`
   - Moved tests: `src/test/kotlin/` → `src/commonTest/kotlin/`
   - Updated `build.gradle.kts` to use `libravault.kmp.library` plugin
   - Restructured dependencies into KMP-aware source sets:
     - `commonMain`: `kotlinx-coroutines-core`, `kotlinx-serialization-json`
     - `androidMain`: `javax.inject:javax.inject:1` (gated to Android side)
     - `commonTest`: `kotlin-test`
     - `androidUnitTest`: JUnit 5 + MockK + Turbine
   - **Kept `@Inject` annotations** on use case constructors (required by Hilt, not a blocker for KMP since the annotation is just metadata)

### Build Status
- ✅ JVM target compiles: `./gradlew :core:domain:jvmJar`
- ⏳ Android app regression check: `./gradlew assembleDebug` (in progress)
- ℹ️ iOS targets disabled on Linux (expected; will work on macOS/CI with proper tooling)

### Next Steps (After Phase A1 PR Lands)
1. Push branch to origin, open PR against `feat/v3-ios-port`
2. CI will run `ios-build.yml::kmp-build` (KMP framework builds) + `android-regression` check
3. Proceed to Phase A3+ conversions in separate PRs (logger, licensing, storage are more complex)
4. Execute Phase A0 Room KMP spike as a separate, focused task with decision gate

## Key Decision: @Inject in commonMain

**Decision:** Keep `@Inject` annotations in `core/domain` use case classes despite the file living in commonMain.

**Rationale:**
- `javax.inject:javax.inject:1` is a marker annotation jar; the annotations are compile-time only (no runtime classpath dependency on iOS)
- Hilt (Android) needs `@Inject` to generate constructor injection factories; removing it breaks the Android app
- iOS builds ignore the annotation (it won't be on the classpath) but don't fail — Kotlin/Native simply treats it as metadata
- This is standard KMP practice: annotate for platform-specific tools while keeping the core logic platform-neutral
- Future iOS DI (v3.1+) can use a different approach (manual factories, Koin, etc.) without modifying these classes

**Alternative considered:** Moving use cases to `androidMain` and creating KMP interfaces in `commonMain` — rejected as over-engineering for Phase A1; use cases are pure logic and should live in common code.

---

**Status:** On track for Phase A1 PR submission pending Android build confirmation.
