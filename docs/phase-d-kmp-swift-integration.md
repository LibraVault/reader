# Phase D: KMP-Swift Integration Roadmap

> **⚠️ STATUS CORRECTION (2026-07-25):** Still accurate as "Planning" — none of D1–D10 below were executed. TestFlight distribution now works via an unrelated, simpler path (a real Xcode App target wrapping hand-written Swift, no KMP frameworks). See **[iOS-TESTFLIGHT-RELEASE-PROCESS.md](iOS-TESTFLIGHT-RELEASE-PROCESS.md)**. This roadmap remains a reasonable plan *if* real KMP domain-layer integration (replacing the current 5-book mock library) becomes a priority — just re-verify effort estimates against current code before starting, since a lot has changed since 2026-07-22.

**Status:** Planning  
**Started:** 2026-07-22  
**Target:** Full KMP framework linking and real domain layer integration

## Overview

Phase D completes the iOS v3.0 development by bridging the Kotlin Multiplatform domain layer (core:domain, core:tts, core:logger, core:storage, core:licensing) with the native Swift UI built in Phase B.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ iOS Swift App (feat/v3-ios-port)                           │
│  ├─ LibraVaultApp.swift (entry point)                      │
│  ├─ Views (LibraryView, ReaderView, SettingsView)          │
│  ├─ AppState (@MainActor observable)                       │
│  └─ DomainBridge (Swift↔KMP interop)                       │
└────────────────────────┬────────────────────────────────────┘
                         │ XCFramework imports (Phase D)
┌────────────────────────v────────────────────────────────────┐
│ KMP XCFrameworks (build/XCFrameworks/)                      │
│  ├─ LibravaultDomain.xcframework                           │
│  ├─ LibravaultTts.xcframework                              │
│  ├─ LibravaultLogger.xcframework                           │
│  ├─ LibravaultStorage.xcframework                          │
│  └─ LibravaultLicensing.xcframework                        │
└────────────────────────┬────────────────────────────────────┘
                         │ Kotlin expect/actual
┌────────────────────────v────────────────────────────────────┐
│ KMP Kotlin Code (dev branch)                               │
│  ├─ core:domain (iosMain stubs → real implementations)     │
│  ├─ core:tts (iOS audio framework)                         │
│  ├─ core:logger (iOS file logging)                         │
│  ├─ core:storage (iOS app sandbox file access)             │
│  └─ core:licensing (iOS IAP / key-based)                   │
└──────────────────────────────────────────────────────────────┘
```

## Phase D Tasks

### D1: KMP Framework Building (Gradle + Kotlin Native)

**Objective:** Create automated build pipeline for iOS XCFrameworks

**Tasks:**
1. Add kotlin-multiplatform plugin to all core modules (if not present)
2. Configure KMP targets for iOS (arm64, simulator, x64)
3. Create Gradle tasks to build XCFrameworks
4. Document framework build process in CI/CD
5. Test builds on macOS runner (CI only - Linux cannot generate Mach-O binaries)

**Files:**
- `kmp-ios-build.gradle.kts` - Framework build config
- `build-logic/convention/KotlinMultiplatformLibraryConventionPlugin.kt` - KMP plugin
- Core module `build.gradle.kts` files - KMP configuration

**Acceptance:** All core modules can build iOS XCFrameworks via:
```bash
./gradlew -f kmp-ios-build.gradle.kts buildIosFrameworks
```

### D2: Swift Package XCFramework Linking

**Objective:** Configure Swift Package Manager to link and use KMP frameworks

**Tasks:**
1. Update `Package.swift` to reference XCFrameworks in `build/XCFrameworks/`
2. Add binaryTarget declarations for each framework
3. Configure framework product linking
4. Add framework search paths for iOS builds
5. Create build script to copy frameworks to Package.swift location

**Files:**
- `ios/LibraVault/Package.swift`
- `ios/LibraVault/build-frameworks.sh` (framework copy script)

**Acceptance:** Swift Package compiles with imported KMP frameworks

### D3: Kotlin→Swift Type Mappings

**Objective:** Define bidirectional type mappings between Kotlin domain objects and Swift models

**Tasks:**
1. Document Kotlin domain model structure (LibraryItem, ReadingProgress, Bookmark, Highlight, etc)
2. Create mapping layer in DomainBridge
3. Implement conversion functions (Kotlin → Swift, Swift → Kotlin)
4. Test mappings with sample data flow

**Mappings:**
- `LibraryItem` (Kotlin) → `BookData` (Swift)
- `ReadingProgress` (Kotlin) → `Double` progress + position tracking (Swift)
- `Bookmark` (Kotlin) → `Bookmark` (Swift model)
- `Highlight` (Kotlin) → `Highlight` (Swift model)
- Use cases return `Flow<T>` in Kotlin → converted to SwiftUI state updates

**Acceptance:** Clear mapping documentation with conversion examples

### D4: Real KMP Integration in DomainBridge

**Objective:** Replace mock data with actual KMP framework calls

**Current Mock Implementation:**
```swift
// Phase B - uses hardcoded sample data
loadMockLibrary() // 5 sample books
```

**Phase D - Real Implementation:**
```swift
// Load via core:domain GetLibraryUseCase
let useCases = kmpDomain.getUseCases()
let libraryItems = try await useCases.getLibraryUseCase()
let books = libraryItems.map { mapToBookData($0) }
```

**Tasks:**
1. Import KMP framework types in DomainBridge
2. Initialize KMP use cases on app launch
3. Replace loadMockLibrary() with real GetLibraryUseCase call
4. Wire scanLibrary() to ScanVaultUseCase
5. Wire readingProgress updates to SaveReadingProgressUseCase
6. Wire bookmarks to BookmarkUseCase
7. Wire highlights to HighlightUseCase

**Acceptance:** iOS app loads real books from core:database via KMP domain layer

### D5: TTS Framework Integration

**Objective:** Connect iOS UI TTS controls to core:tts KMP framework

**Current:** Mock implementation in TTSEngineBridge

**Phase D Implementation:**
```swift
// Replace mock with actual core:tts TtsEngine calls
private var ttsEngine: TtsEngine? // from KMP

func startSpeaking(text: String) async {
    await ttsEngine?.speak(text: text)
}
```

**Tasks:**
1. Import TtsEngine from core:tts framework
2. Initialize TTS engine with iOS audio configuration
3. Implement speak(), pause(), resume(), stop() via framework
4. Handle audio session management
5. Test with sample text in ReaderView

**Acceptance:** ReaderView "Read Aloud" button plays audio via core:tts

### D6: Logger Framework Integration

**Objective:** Connect iOS logging to core:logger KMP framework

**Current:** Console print() only

**Phase D Implementation:**
```swift
// Replace print() with actual core:logger calls
private var logger: LibravaultLogger? // from KMP

func d(tag: String, message: String) {
    logger?.d(message: "[\(tag)] \(message)")
}
```

**Tasks:**
1. Import LibravaultLogger from core:logger framework
2. Initialize logger with iOS file paths
3. Implement d(), i(), w(), e() via framework
4. Set up file rotation for iOS (Documents folder)
5. Connect SettingsView LogViewerView to framework logs

**Acceptance:** Developer logging reads from core:logger file output

### D7: iOS Licensing Integration (core:licensing KMP)

**Objective:** Implement App Store / key-based licensing via KMP

**Tasks:**
1. Implement iOS `IProGate` in core:licensing/iosMain
2. Choose licensing strategy:
   - Option A: Key-based (F-Droid compatible, no App Store needed)
   - Option B: App Store In-App Purchase via StoreKit 2
   - Option C: Hybrid (key fallback + App Store)
3. Implement activateWithKey(), refresh(), purchaseOutcomes() for iOS
4. Secure key storage (app-level encryption, NOT Keychain per policy)
5. Wire SettingsView to licensing UI

**Acceptance:** iOS app can unlock Pro features (strategy TBD)

### D8: iOS Storage Integration (core:storage KMP)

**Objective:** Implement file access and vault scanning for iOS

**Tasks:**
1. Implement iOS `StorageManager` in core:storage/iosMain
2. Implement file picker for vault selection (UIDocumentPickerViewController)
3. Implement file access via app sandbox URLs
4. Implement metadata extraction for iOS (use native frameworks)
5. Wire LibrarySettingsView to add/remove vaults
6. Test with sample PDF/EPUB files

**Acceptance:** iOS app can select vault folders and scan for books

### D9: Testing & Verification

**Objective:** End-to-end testing of KMP integration

**Tests:**
1. Unit tests: Type mapping functions
2. Integration tests: KMP framework linking
3. UI tests: Library loading, book reading, bookmarks/highlights
4. Performance: Library load time, scroll smoothness
5. Manual QA: Full user flow on device/simulator

**Acceptance:** All tests pass, app works smoothly with real KMP framework

### D10: CI/CD Integration

**Objective:** Automate KMP framework builds in GitHub Actions

**Tasks:**
1. Add macOS runner job to `.github/workflows/ios-build.yml`
2. Build KMP frameworks on each commit to dev
3. Upload frameworks as artifacts
4. Download artifacts in iOS build job
5. Document build process

**Acceptance:** iOS builds automatically use latest KMP frameworks

## Implementation Sequence

**Phase D1:** Framework building (Gradle)
**Phase D2:** SPM linking (Swift Package)
**Phase D3:** Type mappings (Documentation + Bridge)
**Phase D4:** Real domain integration (Biggest change)
**Phase D5-8:** Framework integrations (TTS, Logger, Licensing, Storage)
**Phase D9:** Testing & verification
**Phase D10:** CI automation

## Risk Mitigation

| Risk | Mitigation |
|------|-----------|
| KMP not compatible with iOS targets | Early testing on macOS runner |
| Swift interop complexity | Reference kotlin-swift-interop docs |
| Type mapping errors | Extensive logging in bridge layer |
| Framework size | Strip debug symbols, use release builds |
| macOS-only builds | CI runs on macOS, local Linux development uses mock data |

## Success Criteria

- [x] Phase B: iOS UI complete with mock data (DONE)
- [x] Phase C: Domain modules reorganized (DONE)
- [ ] Phase D1: KMP frameworks build successfully
- [ ] Phase D2: SPM links frameworks correctly
- [ ] Phase D3: Type mappings documented and tested
- [ ] Phase D4: Real library loading via KMP domain layer
- [ ] Phase D5: TTS framework integration working
- [ ] Phase D6: Logger framework integration working
- [ ] Phase D7: Licensing framework integration working
- [ ] Phase D8: Storage framework integration working
- [ ] Full iOS app: Fully functional with real KMP backend
- [ ] CI/CD: Automated framework builds and iOS app testing

## Timeline Estimate

- **Phase D1-D3:** 2-3 days (framework setup & linking)
- **Phase D4:** 2-3 days (biggest integration change)
- **Phase D5-D8:** 1 day each (framework integrations)
- **Phase D9:** 1 day (testing & fixes)
- **Phase D10:** 1 day (CI setup)

**Total:** ~2 weeks (requires macOS for actual testing)

## Resources

- [Kotlin Multiplatform Mobile](https://kotlinlang.org/docs/multiplatform-mobile-intro.html)
- [Kotlin/Native interop with Swift](https://kotlinlang.org/docs/native-objc-interop.html)
- [Swift Package Manager](https://swift.org/package-manager/)
- [GitHub Actions macOS runners](https://github.com/actions/runner-images)
